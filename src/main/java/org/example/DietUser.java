package org.example;

import org.semanticweb.owlapi.model.OWLClass;

import java.util.List;

/**
 * A user with the foods they may, must not and especially like to eat.
 * <p>
 * The foods the user wants are the foods that are permitted <em>and</em> favorite, minus the forbidden foods. Permitted
 * and favorite foods are each closed the same way; if one of them is empty, it does not restrict the foods.
 *
 * @param name
 * 		The name of the user
 * @param permitted
 * 		The food classes the user may eat. Foods processed only from these classes are permitted, too. If empty, all
 * 		foods are permitted.
 * @param favorites
 * 		The food classes the user likes best. Foods processed only from these classes are favorite, too. If empty, all
 * 		foods are favorite.
 * @param forbidden
 * 		The food classes the user must not eat. Foods processed from these classes are forbidden, too.
 */
public record DietUser(String name, List<OWLClass> permitted, List<OWLClass> favorites, List<OWLClass> forbidden) {

	public DietUser {
		permitted = List.copyOf(permitted);
		favorites = List.copyOf(favorites);
		forbidden = List.copyOf(forbidden);
	}
}
