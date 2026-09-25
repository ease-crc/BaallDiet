package org.example;

import jakarta.annotation.PreDestroy;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Class to manage an {@link OWLReasoner}.
 */
public abstract class Manager<C extends OWLReasonerConfiguration> {

	private static final Logger LOGGER = LoggerFactory.getLogger(Manager.class);

	/**
	 * The configuration of the managed reasoner.
	 */
	private final C configuration;

	/**
	 * The currently managed {@link OWLReasoner}.
	 *
	 * This must not be final because some reasoners, such as Konclude via OWLlink,
	 * do not support ontology changes. In that case, the reasoner has to be
	 * recreated for the changed ontology.
	 */
	private OWLReasoner reasoner;

	/**
	 * Creates a new ReasonerManager.
	 *
	 * @param ontology
	 *         The ontology of the managed reasoner
	 * @param configuration
	 *         The configuration of the managed reasoner
	 */
	@Autowired
	protected Manager(final OWLOntology ontology, final C configuration) {
		this.configuration = configuration;
		reasoner = createReasoner(ontology, configuration);
	}

	/**
	 * Creates an {@link OWLReasoner} that makes the given ontology accessible with
	 * the given {@link OWLReasonerConfiguration}.
	 *
	 * @param ontology
	 *         The ontology that should be reasoned over
	 * @param configuration
	 *         The configuration of the {@link OWLReasoner} to be created
	 *
	 * @return The created reasoner
	 */
	protected abstract OWLReasoner createReasoner(final OWLOntology ontology, final C configuration);

	/**
	 * @return The configuration of the managed reasoner
	 */
	protected C getConfiguration() {
		return configuration;
	}

	/**
	 * @return The currently managed {@link OWLReasoner}
	 */
	public synchronized OWLReasoner getReasoner() {
		return reasoner;
	}

	/**
	 * Replaces the currently managed reasoner with a fresh reasoner for the given ontology.
	 * <p>
	 * This is required for reasoners that cannot handle ontology changes via
	 * {@link OWLReasoner#flush()}.
	 *
	 * @param ontology
	 *         The changed ontology that the new reasoner should reason over
	 *
	 * @return The newly created reasoner
	 */
	public synchronized OWLReasoner recreateReasoner(final OWLOntology ontology) {
		LOGGER.info("Creating replacement reasoner for changed ontology.");

		final OWLReasoner oldReasoner = reasoner;
		final OWLReasoner newReasoner = createReasoner(ontology, configuration);

		reasoner = newReasoner;

		if (oldReasoner != null) {
			LOGGER.info("Disposing old reasoner after successful replacement.");

			try {
				oldReasoner.dispose();
			} catch (final Exception e) {
				LOGGER.warn("Exception while disposing old reasoner.", e);
			}
		}

		LOGGER.info("Replacement reasoner is ready.");

		return newReasoner;
	}

	/**
	 * Releases the managed reasoner.
	 */
	@PreDestroy
	public synchronized void stopReasoner() {
		if (reasoner == null) {
			return;
		}

		LOGGER.info("Disposing managed reasoner.");

		reasoner.dispose();
		reasoner = null;
	}
}