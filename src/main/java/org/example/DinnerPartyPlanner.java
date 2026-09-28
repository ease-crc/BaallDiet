package org.example;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 * Computes the foods that all users of a dinner party may eat: for each user, the foods that are permitted
 * without those that are provably forbidden (see {@link DinnerPartyAxioms}), intersected over all users.
 * <p>
 * The foods of users are not cached: a Konclude knowledge base is created with the axioms of all users and classified
 * on every call.
 */
final class DinnerPartyPlanner {

	/**
	 * The steps reported by {@link #computeFoods}.
	 */
	static final List<String> STEP_LABELS = List.of(
			"Creating knowledge base",
			"Classifying ontology",
			"Querying foods of users"
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
			final IntConsumer stepListener
	) {
		LOGGER.info(
				"Computing dinner party foods for {}.",
				users.stream().map(DietUser::name).collect(Collectors.joining(", "))
		);

		final OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
		final OWLClass food = df.getOWLClass(IRI.create(DinnerPartyAxioms.FOOD_NS + "Food"));
		final DinnerPartyAxioms partyAxioms = new DinnerPartyAxioms(
				users,
				df.getOWLObjectProperty(IRI.create(DinnerPartyAxioms.FOOD_NS + "processed_from")),
				df
		);

		LOGGER.info("Adding {} additional axioms.", partyAxioms.getAxioms().size());

		stepListener.accept(0);
		final OWLReasoner reasoner = koncludeManager.createUnmanagedReasoner(ontology, partyAxioms.getAxioms());
		Set<OWLClass> partyFoods = null;

		try {
			stepListener.accept(1);
			reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);

			stepListener.accept(2);

			for (final DietUser user : users) {
				final UserFoods userFoods = foodsOf(reasoner, food, partyAxioms.getUserClasses(user), partyAxioms);
				final Set<OWLClass> foods = new LinkedHashSet<>(userFoods.total());

				LOGGER.info(
						"{} may eat {} foods in total ({} positive, {} negative).",
						user.name(),
						userFoods.total().size(),
						userFoods.positive().size(),
						userFoods.negative().size()
				);
				LOGGER.info("{} positive foods: {}", user.name(), names(userFoods.positive()));
				LOGGER.info("{} negative foods: {}", user.name(), names(userFoods.negative()));
				LOGGER.info("{} total foods: {}", user.name(), names(userFoods.total()));

				if (partyFoods == null) {
					partyFoods = foods;
				} else {
					partyFoods.retainAll(foods);
				}

			}
		} finally {
			reasoner.dispose();
		}

		LOGGER.info("The dinner party may eat {} foods.", partyFoods.size());

		return partyFoods;
	}

	/**
	 * The foods of a user.
	 *
	 * @param positive
	 * 		The permitted foods, i.e., all foods if the user does not restrict them
	 * @param negative
	 * 		The provably forbidden foods
	 * @param total
	 * 		The foods that are permitted and not forbidden
	 */
	private record UserFoods(Set<OWLClass> positive, Set<OWLClass> negative, Set<OWLClass> total) {
	}

	private static UserFoods foodsOf(
			final OWLReasoner reasoner,
			final OWLClass food,
			final DinnerPartyAxioms.UserClasses classes,
			final DinnerPartyAxioms partyAxioms
	) {
		final Set<OWLClass> positive = subClasses(reasoner, classes.permitted().orElse(food), partyAxioms);
		final Set<OWLClass> negative = classes.forbidden()
				.map(c -> subClasses(reasoner, c, partyAxioms))
				.orElseGet(LinkedHashSet::new);
		final Set<OWLClass> total = new LinkedHashSet<>(positive);
		total.removeAll(negative);

		return new UserFoods(positive, negative, total);
	}

	private static String names(final Set<OWLClass> classes) {
		return classes.stream()
				.map(c -> c.getIRI().getShortForm())
				.sorted()
				.collect(Collectors.joining(", ", "[", "]"));
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
