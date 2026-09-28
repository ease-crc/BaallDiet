package org.example;

import org.semanticweb.owlapi.model.OWLClass;

import java.util.List;

/**
 * A user with the foods they may and must not eat.
 * <p>
 * The foods the user may eat are the permitted foods minus the forbidden foods.
 *
 * @param name
 * 		The name of the user
 * @param permitted
 * 		The food classes the user may eat. Foods processed only from these classes are permitted, too. If empty, all
 * 		foods are permitted.
 * @param forbidden
 * 		The food classes the user must not eat. Foods processed from these classes are forbidden, too.
 */
public record DietUser(String name, List<OWLClass> permitted, List<OWLClass> forbidden) {

	public DietUser {
		permitted = List.copyOf(permitted);
		forbidden = List.copyOf(forbidden);
	}
}
