package org.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Persists the {@link DietUser}s and the guests of the dinner party as JSON in the temporary folder of the operating
 * system, so that they survive restarts of the application.
 * <p>
 * Classes are stored by IRI. On loading, classes that no longer exist in the ontology are dropped.
 */
final class DinnerPartyStore {

	private static final Logger LOGGER = LoggerFactory.getLogger(DinnerPartyStore.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * The state of the dinner party.
	 *
	 * @param users
	 * 		All users
	 * @param guestNames
	 * 		The names of the users that attend the dinner party
	 */
	record State(List<DietUser> users, Set<String> guestNames) {
	}

	/**
	 * The JSON representation of the state.
	 */
	public record Json(List<JsonUser> users, List<String> guests) {
	}

	/**
	 * The JSON representation of a {@link DietUser}.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JsonUser(String name, List<String> permitted, List<String> forbidden) {
	}

	private final Path file;
	private final OWLDataFactory df;
	private final Predicate<OWLClass> existsInOntology;

	/**
	 * @param file
	 * 		The file to persist to
	 * @param existsInOntology
	 * 		Tests whether a class still exists; classes that do not are dropped when loading
	 */
	DinnerPartyStore(final Path file, final OWLDataFactory df, final Predicate<OWLClass> existsInOntology) {
		this.file = file;
		this.df = df;
		this.existsInOntology = existsInOntology;
	}

	/**
	 * @return The default file, in the temporary folder of the operating system ({@code java.io.tmpdir}, which is
	 * resolved appropriately on both Windows and macOS)
	 */
	static Path defaultFile() {
		return Path.of(System.getProperty("java.io.tmpdir"), "bkb-script-3", "dinner-party.json");
	}

	/**
	 * @return The persisted state, or an empty state if there is none or it cannot be read
	 */
	State load() {
		if (!Files.isRegularFile(file)) {
			return new State(List.of(), Set.of());
		}

		try {
			final Json json = MAPPER.readValue(file.toFile(), Json.class);

			final List<DietUser> users = new ArrayList<>();
			final Set<String> names = new LinkedHashSet<>();

			for (final JsonUser user : nullToEmpty(json.users())) {
				// ignore duplicates and broken entries instead of failing on them
				if (user.name() == null || !names.add(user.name())) {
					continue;
				}

				users.add(new DietUser(
						user.name(),
						toClasses(user.permitted()),
						toClasses(user.forbidden())
				));
			}

			final Set<String> guests = new LinkedHashSet<>(nullToEmpty(json.guests()));
			guests.retainAll(names);

			LOGGER.info("Loaded {} dinner party users ({} attending) from {}.", users.size(), guests.size(), file);

			return new State(users, guests);
		} catch (final IOException | RuntimeException e) {
			LOGGER.warn("Could not read the dinner party from {}. Starting with an empty dinner party.", file, e);
			return new State(List.of(), Set.of());
		}
	}

	/**
	 * Writes the given state. Failures are logged, not thrown, as persisting must not disturb the user.
	 */
	void save(final List<DietUser> users, final Set<String> guestNames) {
		final Json json = new Json(
				users.stream()
						.map(u -> new JsonUser(
								u.name(),
								toIris(u.permitted()),
								toIris(u.forbidden())
						))
						.toList(),
				List.copyOf(guestNames)
		);

		try {
			Files.createDirectories(file.getParent());

			// write next to the target and move, so that a crash never leaves a half-written file
			final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
			MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), json);
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (final IOException | RuntimeException e) {
			LOGGER.warn("Could not save the dinner party to {}.", file, e);
		}
	}

	/**
	 * @return Five example guests with different diets and allergies. Classes that the ontology does not have
	 * are left out.
	 */
	List<DietUser> exampleGuests() {
		return List.of(
				new DietUser("Ali (halal)", food("Rigid_Halal_Food"), food()),
				new DietUser("Bernd (no pet)", food(), food("AnyPet_as_Food")),
				new DietUser("Dana (kosher)", food("Rigid_Kosher_Food"), food()),
				new DietUser("John (ovo-lacto-pescetarian)", food("OvoLactoPescetarian_Food"), food()),
				new DietUser("Nancy (pure; nut allergy)", food("PureFood"), food("NutOrSeed"))
		);
	}

	private List<OWLClass> food(final String... names) {
		return java.util.Arrays.stream(names)
				.map(name -> df.getOWLClass(IRI.create(DinnerPartyAxioms.FOOD_NS + name)))
				.filter(existsInOntology)
				.toList();
	}

	private List<OWLClass> toClasses(final List<String> iris) {
		return nullToEmpty(iris).stream()
				.map(iri -> df.getOWLClass(IRI.create(iri)))
				.filter(existsInOntology)
				.toList();
	}

	private static List<String> toIris(final List<OWLClass> classes) {
		return classes.stream().map(c -> c.getIRI().toString()).toList();
	}

	private static <T> List<T> nullToEmpty(final List<T> list) {
		return list == null ? List.of() : list;
	}
}
