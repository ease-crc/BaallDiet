package org.example;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the axioms that define the permitted, favorite and forbidden foods of {@link DietUser}s.
 * <p>
 * For each user {@code U}:
 * <ul>
 *     <li>{@code U_Permitted_Food ≡ ∃processed_from.U_Permitted_Food ⊓ ∀processed_from.U_Permitted_Food}, and each
 *     permitted class is a subclass of it, i.e., foods processed only from permitted foods are permitted (the same
 *     closure as the selection method). Without permitted classes, all foods are permitted.</li>
 *     <li>The same for the favorite classes with {@code U_Favorite_Food}. The user wants the foods that are permitted
 *     <em>and</em> favorite; without favorite classes, all foods are favorite.</li>
 *     <li>Each forbidden class is a subclass of {@code U_Forbidden_Food}, and
 *     {@code ∃processed_from.U_Forbidden_Food ⊑ U_Forbidden_Food}, i.e., foods processed from forbidden foods are
 *     forbidden, too.</li>
 *     <li>Following the ontology's modelling, these foods are linked to the user's diet {@code U_Diet ⊑ Diet} via
 *     {@code permittedBy} and {@code forbiddenBy}.</li>
 * </ul>
 * Deliberately, there is no axiom {@code U_Food ≡ U_Permitted_Food ⊓ ¬U_Forbidden_Food}: as the ontology rarely states
 * disjointness, the reasoner could almost never prove that a food is not forbidden. Instead, the foods of a user are
 * the common subclasses of {@code U_Permitted_Food} and {@code U_Favorite_Food} that are not subclasses of
 * {@code U_Forbidden_Food}, i.e., foods are excluded if they are provably forbidden (see {@link DinnerPartyPlanner}).
 */
final class DinnerPartyAxioms {

	static final String FOOD_NS = "http://ontologies.baall.de/FOOD#";

	/**
	 * Namespace of the generated classes. They are not persisted.
	 */
	private static final String USERS_NS = "http://ontologies.baall.de/FOOD_Users#";

	/**
	 * The classes of the permitted, favorite and forbidden foods of a user.
	 *
	 * @param permitted
	 * 		The class whose subclasses are the permitted foods, if the user restricts the permitted foods
	 * @param favorite
	 * 		The class whose subclasses are the favorite foods, if the user has favorite foods
	 * @param forbidden
	 * 		The class whose subclasses are the forbidden foods, if the user forbids any foods
	 */
	record UserClasses(Optional<OWLClass> permitted, Optional<OWLClass> favorite, Optional<OWLClass> forbidden) {
	}

	/**
	 * The generated axioms.
	 */
	private final Set<OWLAxiom> axioms = new LinkedHashSet<>();

	/**
	 * The generated classes, which should not be part of any result.
	 */
	private final Set<OWLClass> generatedClasses = new LinkedHashSet<>();

	private final Map<DietUser, UserClasses> userClasses = new LinkedHashMap<>();

	/**
	 * @param users
	 * 		The users, whose names must be unique after {@link #sanitize(String) sanitization}
	 * @param processedFrom
	 * 		The property that relates foods to the foods they are processed from
	 */
	DinnerPartyAxioms(final List<DietUser> users, final OWLObjectProperty processedFrom, final OWLDataFactory df) {
		users.forEach(user -> userClasses.put(user, addUser(user, processedFrom, df)));
	}

	private UserClasses addUser(final DietUser user, final OWLObjectProperty processedFrom, final OWLDataFactory df) {
		final String prefix = "User_" + sanitize(user.name());

		final OWLClass diet = newClass(prefix + "_Diet", df);
		axioms.add(df.getOWLSubClassOfAxiom(diet, df.getOWLClass(IRI.create(FOOD_NS + "Diet"))));

		final OWLObjectProperty permittedBy = df.getOWLObjectProperty(IRI.create(FOOD_NS + "permittedBy"));
		final OWLObjectProperty forbiddenBy = df.getOWLObjectProperty(IRI.create(FOOD_NS + "forbiddenBy"));

		final Optional<OWLClass> permitted = addClosure(
				prefix + "_Permitted_Food", user.permitted(), processedFrom, df, permittedBy, diet);
		final Optional<OWLClass> favorite = addClosure(
				prefix + "_Favorite_Food", user.favorites(), processedFrom, df, null, diet);

		if (user.forbidden().isEmpty()) {
			return new UserClasses(permitted, favorite, Optional.empty());
		}

		final OWLClass forbidden = newClass(prefix + "_Forbidden_Food", df);
		user.forbidden().forEach(c -> axioms.add(df.getOWLSubClassOfAxiom(c, forbidden)));
		axioms.add(df.getOWLSubClassOfAxiom(df.getOWLObjectSomeValuesFrom(processedFrom, forbidden), forbidden));
		axioms.add(df.getOWLSubClassOfAxiom(forbidden, df.getOWLObjectSomeValuesFrom(forbiddenBy, diet)));

		return new UserClasses(permitted, favorite, Optional.of(forbidden));
	}

	/**
	 * Adds the class whose subclasses are the given classes and the foods that are processed only from them.
	 *
	 * @param linkProperty
	 * 		The property that links the foods to the user's diet, or {@code null} for no link
	 *
	 * @return The added class, or empty if there are no given classes
	 */
	private Optional<OWLClass> addClosure(
			final String fragment,
			final List<OWLClass> classes,
			final OWLObjectProperty processedFrom,
			final OWLDataFactory df,
			final OWLObjectProperty linkProperty,
			final OWLClass diet
	) {
		if (classes.isEmpty()) {
			return Optional.empty();
		}

		final OWLClass closure = newClass(fragment, df);
		axioms.add(df.getOWLEquivalentClassesAxiom(
				closure,
				df.getOWLObjectIntersectionOf(
						df.getOWLObjectSomeValuesFrom(processedFrom, closure),
						df.getOWLObjectAllValuesFrom(processedFrom, closure)
				)
		));
		classes.forEach(c -> axioms.add(df.getOWLSubClassOfAxiom(c, closure)));

		if (linkProperty != null) {
			axioms.add(df.getOWLSubClassOfAxiom(closure, df.getOWLObjectSomeValuesFrom(linkProperty, diet)));
		}

		return Optional.of(closure);
	}

	private OWLClass newClass(final String fragment, final OWLDataFactory df) {
		final OWLClass owlClass = df.getOWLClass(IRI.create(USERS_NS + fragment));
		generatedClasses.add(owlClass);
		axioms.add(df.getOWLDeclarationAxiom(owlClass));
		return owlClass;
	}

	static String sanitize(final String value) {
		return value.trim()
				.replaceAll("\\s+", "_")
				.replaceAll("[^A-Za-z0-9_\\-]", "");
	}

	Set<OWLAxiom> getAxioms() {
		return axioms;
	}

	Set<OWLClass> getGeneratedClasses() {
		return generatedClasses;
	}

	UserClasses getUserClasses(final DietUser user) {
		return userClasses.get(user);
	}
}
