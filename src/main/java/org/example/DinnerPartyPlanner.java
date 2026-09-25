package org.example;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 * Computes the foods that all users of a dinner party may eat: for each user, the foods that are permitted and favorite
 * without those that are provably forbidden (see {@link DinnerPartyAxioms}), intersected over all users.
 * <p>
 * The foods of a class set are looked up in a {@link FoodResultCache} first. Only if there are users with classes that
 * are not cached for the current axioms of the ontology, a Konclude knowledge base is created (with the axioms of only
 * these users) and classified.
 */
final class DinnerPartyPlanner {

	/**
	 * The steps reported by {@link #computeFoods}. The middle steps are skipped if all foods are cached.
	 */
	static final List<String> STEP_LABELS = List.of(
			"Looking up known foods",
			"Creating knowledge base",
			"Classifying ontology",
			"Querying foods of new users"
	);

	private static final Logger LOGGER = LoggerFactory.getLogger(DinnerPartyPlanner.class);

	private DinnerPartyPlanner() {
	}

	/**
	 * Computes the foods that all given users may eat. This takes long and must not be called on the EDT.
	 *
	 * @param stepListener
	 * 		Receives the index of the step in {@link #STEP_LABELS} that is being started
	 *
	 * @return The foods that all given users may eat. The ontology is not changed.
	 */
	static Set<OWLClass> computeFoods(
			final List<DietUser> users,
			final OWLOntology ontology,
			final KoncludeManager koncludeManager,
			final FoodResultCache cache,
			final IntConsumer stepListener
	) {
		stepListener.accept(0);

		final List<DietUser> unknown = users.stream().filter(u -> !isCached(u, cache)).toList();

		LOGGER.info(
				"Computing dinner party foods for {}; {} of {} users are not cached.",
				users.stream().map(DietUser::name).collect(Collectors.joining(", ")),
				unknown.size(),
				users.size()
		);

		if (!unknown.isEmpty()) {
			computeAndCache(unknown, ontology, koncludeManager, cache, stepListener);
		}

		Set<OWLClass> partyFoods = null;

		for (final DietUser user : users) {
			final Set<OWLClass> foods = foodsOf(user, cache);

			if (partyFoods == null) {
				partyFoods = foods;
			} else {
				partyFoods.retainAll(foods);
			}
		}

		LOGGER.info("The dinner party may eat {} foods.", partyFoods.size());

		return partyFoods;
	}

	/**
	 * A part of the foods of a user that is looked up or cached on its own.
	 *
	 * @param kind
	 * 		The kind of foods
	 * @param classes
	 * 		The classes the foods are computed for
	 */
	private record Part(FoodResultCache.Kind kind, List<OWLClass> classes) {

		Optional<Set<OWLClass>> cached(final FoodResultCache cache) {
			return cache.get(kind, classes);
		}
	}

	/**
	 * @return The parts whose intersection are the foods that are permitted and favorite, i.e., one part for each of
	 * the permitted and the favorite classes that the user has, or all foods if there are none
	 */
	private static List<Part> allowedParts(final DietUser user) {
		final List<Part> parts = new ArrayList<>();

		if (!user.permitted().isEmpty()) {
			parts.add(new Part(FoodResultCache.Kind.PERMITTED, user.permitted()));
		}

		// favorite foods are closed like permitted foods, so they share the cache entries
		if (!user.favorites().isEmpty()) {
			parts.add(new Part(FoodResultCache.Kind.PERMITTED, user.favorites()));
		}

		if (parts.isEmpty()) {
			parts.add(new Part(FoodResultCache.Kind.ALL, List.of()));
		}

		return parts;
	}

	private static Optional<Part> forbiddenPart(final DietUser user) {
		return user.forbidden().isEmpty()
		       ? Optional.empty()
		       : Optional.of(new Part(FoodResultCache.Kind.FORBIDDEN, user.forbidden()));
	}

	private static boolean isCached(final DietUser user, final FoodResultCache cache) {
		return allowedParts(user).stream().allMatch(part -> part.cached(cache).isPresent())
				&& forbiddenPart(user).map(part -> part.cached(cache).isPresent()).orElse(true);
	}

	/**
	 * @return The foods of the user from the cache, where all of them must be cached
	 */
	private static Set<OWLClass> foodsOf(final DietUser user, final FoodResultCache cache) {
		Set<OWLClass> foods = null;

		for (final Part part : allowedParts(user)) {
			final Set<OWLClass> partFoods = part.cached(cache).orElseThrow();

			if (foods == null) {
				foods = new LinkedHashSet<>(partFoods);
			} else {
				foods.retainAll(partFoods);
			}
		}

		final Optional<Part> forbidden = forbiddenPart(user);

		if (forbidden.isPresent()) {
			foods.removeAll(forbidden.get().cached(cache).orElseThrow());
		}

		return foods;
	}

	/**
	 * Classifies the ontology with the axioms of the given users and caches their permitted and forbidden foods.
	 */
	private static void computeAndCache(
			final List<DietUser> users,
			final OWLOntology ontology,
			final KoncludeManager koncludeManager,
			final FoodResultCache cache,
			final IntConsumer stepListener
	) {
		final OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
		final OWLClass food = df.getOWLClass(IRI.create(DinnerPartyAxioms.FOOD_NS + "Food"));
		final DinnerPartyAxioms partyAxioms = new DinnerPartyAxioms(
				users,
				df.getOWLObjectProperty(IRI.create(DinnerPartyAxioms.FOOD_NS + "processed_from")),
				df
		);

		LOGGER.info(
				"Adding {} additional axioms for {}.",
				partyAxioms.getAxioms().size(),
				users.stream().map(DietUser::name).collect(Collectors.joining(", "))
		);

		stepListener.accept(1);
		final OWLReasoner reasoner = koncludeManager.createUnmanagedReasoner(ontology, partyAxioms.getAxioms());

		try {
			stepListener.accept(2);
			reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);

			stepListener.accept(3);

			for (final DietUser user : users) {
				final DinnerPartyAxioms.UserClasses classes = partyAxioms.getUserClasses(user);

				if (classes.permitted().isEmpty() && classes.favorite().isEmpty()) {
					cache.put(FoodResultCache.Kind.ALL, List.of(), subClasses(reasoner, food, partyAxioms));
				}

				classes.permitted().ifPresent(c -> cache.put(
						FoodResultCache.Kind.PERMITTED,
						user.permitted(),
						subClasses(reasoner, c, partyAxioms)
				));
				classes.favorite().ifPresent(c -> cache.put(
						FoodResultCache.Kind.PERMITTED,
						user.favorites(),
						subClasses(reasoner, c, partyAxioms)
				));
				classes.forbidden().ifPresent(c -> cache.put(
						FoodResultCache.Kind.FORBIDDEN,
						user.forbidden(),
						subClasses(reasoner, c, partyAxioms)
				));

				LOGGER.info("Computed the foods of {}.", user.name());
			}

			cache.save();
		} finally {
			reasoner.dispose();
		}
	}

	/**
	 * @return The named, satisfiable subclasses of the given class, without the generated classes
	 */
	private static Set<OWLClass> subClasses(
			final OWLReasoner reasoner,
			final OWLClass owlClass,
			final DinnerPartyAxioms partyAxioms
	) {
		return reasoner.getSubClasses(owlClass, false)
				.entities()
				.filter(c -> !c.isOWLNothing())
				.filter(c -> !partyAxioms.getGeneratedClasses().contains(c))
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}
}
