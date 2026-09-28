package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Caches the foods that are permitted or forbidden for a set of classes, by a hash of the axioms of the ontology.
 * <p>
 * The foods of a user only depend on the ontology and on the user's own classes, not on other users, as the axioms of
 * different users do not interact (see {@link DinnerPartyAxioms}). Thus, the results are cached per set of classes,
 * so that, e.g., changing who attends a dinner party only needs to compute the users that were never seen before, and
 * a custom diet with the classes of a user gets the foods of that user.
 * <p>
 * A cached result is only valid for the very same axioms. Therefore, all results belong to the hash of the ontology's
 * axioms, and are dropped when the ontology changes ({@link #ontologyChanged()}). The results are persisted in the
 * temporary folder of the operating system together with the hash, so that they survive restarts of the application as
 * long as the ontology is the same. On loading, results of a different hash are ignored.
 * <p>
 * Why this caches instead of asking Konclude for the foods without a new knowledge base: Konclude 0.7.0 crashes when
 * it gets a subclass query for an anonymous class expression via OWLlink (which would allow to query the foods on the
 * already classified base knowledge base), and adding the axioms of a user to the classified knowledge base ({@code
 * Tell}) makes it classify again, which takes about three times as long as classifying a fresh knowledge base.
 * <p>
 * Methods that need the hash must not be called on the EDT, as computing it takes seconds for a large ontology.
 */
final class FoodResultCache {

	/**
	 * The kinds of cached results.
	 */
	enum Kind {
		/**
		 * The foods that are permitted for a set of classes: the classes, their subclasses, and the foods that are
		 * processed only from those.
		 */
		PERMITTED,
		/**
		 * The foods that are forbidden for a set of classes: the classes, their subclasses, and the foods that are
		 * processed from those.
		 */
		FORBIDDEN,
		/**
		 * All foods. Takes no classes.
		 */
		ALL
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(FoodResultCache.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Marks an IRI in the food namespace, to keep the file small.
	 */
	private static final String FOOD_PREFIX = ":";

	/**
	 * The JSON representation of the cache.
	 */
	public record Json(String hash, Map<String, List<String>> entries) {
	}

	private final Path file;
	private final OWLOntology ontology;
	private final OWLDataFactory df;

	/**
	 * The hash of the axioms of the ontology, or {@code null} if it has to be computed.
	 */
	private String hash;

	/**
	 * The results for {@link #hash}, or {@code null} if not loaded yet.
	 */
	private Map<String, Set<OWLClass>> entries;

	/**
	 * @param file
	 * 		The file to persist to
	 * @param ontology
	 * 		The ontology whose axioms are hashed; must only be changed with a subsequent call to
	 *        {@link #ontologyChanged()}
	 */
	FoodResultCache(final Path file, final OWLOntology ontology) {
		this.file = file;
		this.ontology = ontology;
		this.df = ontology.getOWLOntologyManager().getOWLDataFactory();
	}

	/**
	 * @return The default file, in the temporary folder of the operating system
	 */
	static Path defaultFile() {
		return Path.of(System.getProperty("java.io.tmpdir"), "bkb-script-3", "food-cache.json");
	}

	/**
	 * Drops all results, as they are only valid for the axioms of the ontology they were computed for. Call this
	 * after axioms were added to or removed from the ontology.
	 */
	synchronized void ontologyChanged() {
		hash = null;
		entries = null;
	}

	/**
	 * @return The cached foods for the given classes, if they are known for the current axioms of the ontology
	 */
	synchronized Optional<Set<OWLClass>> get(final Kind kind, final Collection<OWLClass> classes) {
		final Set<OWLClass> foods = load().get(key(kind, classes));

		LOGGER.info("{} foods for {} classes: {}.", kind, classes.size(), foods == null ? "not cached" : "cached");

		return Optional.ofNullable(foods);
	}

	/**
	 * Caches the foods for the given classes for the current axioms of the ontology. Call {@link #save()} to persist
	 * them.
	 */
	synchronized void put(final Kind kind, final Collection<OWLClass> classes, final Set<OWLClass> foods) {
		load().put(key(kind, classes), new LinkedHashSet<>(foods));
	}

	/**
	 * Persists the results together with the hash of the axioms they are valid for. Failures are logged, not thrown.
	 */
	synchronized void save() {
		final Map<String, Set<OWLClass>> current = load();

		final Map<String, List<String>> encoded = new LinkedHashMap<>();
		current.forEach((key, foods) -> encoded.put(key, foods.stream().map(FoodResultCache::encode).toList()));

		try {
			Files.createDirectories(file.getParent());

			// write next to the target and move, so that a crash never leaves a half-written file
			final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
			MAPPER.writeValue(temporary.toFile(), new Json(hash, encoded));
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (final IOException | RuntimeException e) {
			LOGGER.warn("Could not save the food cache to {}.", file, e);
		}
	}

	private static String key(final Kind kind, final Collection<OWLClass> classes) {
		return kind + ":" + classes.stream()
				.map(c -> c.getIRI().toString())
				.distinct()
				.sorted()
				.collect(Collectors.joining(","));
	}

	/**
	 * @return The results for the current hash, loaded from the file if it was written for the same hash
	 */
	private Map<String, Set<OWLClass>> load() {
		if (entries != null) {
			return entries;
		}

		if (hash == null) {
			final long startNs = System.nanoTime();
			hash = hashAxioms(ontology);
			LOGGER.info("Hashed the axioms of the ontology in {} ms: {}", (System.nanoTime() - startNs) / 1_000_000, hash);
		}

		entries = new HashMap<>();

		if (!Files.isRegularFile(file)) {
			return entries;
		}

		try {
			final Json json = MAPPER.readValue(file.toFile(), Json.class);

			if (hash.equals(json.hash()) && json.entries() != null) {
				json.entries().forEach((key, iris) -> entries.put(
						key,
						iris.stream().map(this::decode).collect(Collectors.toCollection(LinkedHashSet::new))
				));
				LOGGER.info("Loaded {} cached food results from {}.", entries.size(), file);
			} else {
				LOGGER.info("Ignoring the food cache in {}, as the ontology has changed.", file);
			}
		} catch (final IOException | RuntimeException e) {
			LOGGER.warn("Could not read the food cache from {}. Starting with an empty cache.", file, e);
			entries.clear();
		}

		return entries;
	}

	private static String encode(final OWLClass owlClass) {
		final String iri = owlClass.getIRI().toString();
		return iri.startsWith(DinnerPartyAxioms.FOOD_NS)
		       ? FOOD_PREFIX + iri.substring(DinnerPartyAxioms.FOOD_NS.length())
		       : iri;
	}

	private OWLClass decode(final String encoded) {
		return df.getOWLClass(IRI.create(
				encoded.startsWith(FOOD_PREFIX) ? DinnerPartyAxioms.FOOD_NS + encoded.substring(1) : encoded
		));
	}

	private static String hashAxioms(final OWLOntology ontology) {
		final String axioms = ontology.logicalAxioms(Imports.INCLUDED)
				.map(Object::toString)
				.sorted()
				.collect(Collectors.joining("\n"));

		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest(axioms.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
