package org.example;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.owllink.OWLlinkReasonerConfigurationImpl;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Class to manage the connection to Konclude.
 */
@Component
@Lazy
public class KoncludeManager extends Manager<OWLlinkReasonerConfigurationImpl> {

	/**
	 * The {@link Logger} of this class.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(KoncludeManager.class);

	/**
	 * The {@link AxiomType}s that are not supported by Konclude.
	 */
	private static final Set<AxiomType<?>> UNSUPPORTED_TYPES = Set.of(
			AxiomType.HAS_KEY,
			AxiomType.DATATYPE_DEFINITION
	);

	private final KoncludeServer server;

	@Autowired
	public KoncludeManager(
			final OntologyManager ontologyManager,
			final KoncludeProperties properties,
			final KoncludeServer server
	) {
		super(
				awaitServer(ontologyManager.getOntology(), server),
				new OWLlinkReasonerConfigurationImpl(properties.owllinkserver())
		);
		this.server = server;
	}

	/**
	 * Waits for the Konclude server before the super constructor creates the first reasoner.
	 */
	private static OWLOntology awaitServer(final OWLOntology ontology, final KoncludeServer server) {
		server.awaitReady();
		return ontology;
	}

	@Override
	protected OWLReasoner createReasoner(
			final OWLOntology ontology,
			final OWLlinkReasonerConfigurationImpl configuration
	) {
		// the knowledge base of the managed reasoner is kept, e.g., for reuse by the next start
		return createReasoner(ontology, Set.of(), configuration, false, () -> {
		});
	}

	/**
	 * Creates a reasoner over the given ontology extended by the given axioms. Neither the ontology nor the managed
	 * reasoner is changed. The caller must dispose the created reasoner to free Konclude's memory.
	 * <p>
	 * As Konclude does not return the memory of released knowledge bases to the operating system, the reasoner uses a
	 * temporary Konclude process, which is terminated when the reasoner is disposed. Only if no Konclude executable is
	 * available, the reasoner uses the managed Konclude and releases its knowledge base there.
	 *
	 * @param ontology
	 *         The ontology to reason over
	 * @param additionalAxioms
	 *         Axioms that the reasoner considers in addition to those of the ontology
	 *
	 * @return The created reasoner
	 */
	public OWLReasoner createUnmanagedReasoner(
			final OWLOntology ontology,
			final Collection<? extends OWLAxiom> additionalAxioms
	) {
		final Optional<KoncludeServer.TemporaryKonclude> temporary = server.startTemporary();

		if (temporary.isEmpty()) {
			return createReasoner(ontology, additionalAxioms, getConfiguration(), true, () -> {
			});
		}

		try {
			return createReasoner(
					ontology,
					additionalAxioms,
					new OWLlinkReasonerConfigurationImpl(temporary.get().url()),
					false,
					temporary.get()::close
			);
		} catch (final RuntimeException e) {
			temporary.get().close();
			throw e;
		}
	}

	private OWLReasoner createReasoner(
			final OWLOntology ontology,
			final Collection<? extends OWLAxiom> additionalAxioms,
			final OWLlinkReasonerConfigurationImpl configuration,
			final boolean releaseOnDispose,
			final Runnable afterDispose
	) {
		final long startNs = System.nanoTime();

		final OWLOntology copy = createKoncludeFriendlyCopy(ontology, additionalAxioms);

		LOGGER.info(
				"Creating Konclude reasoner for ontology copy with {} axioms.",
				copy.getAxiomCount(Imports.INCLUDED)
		);

		/*
		 * Creating the reasoner already communicates with Konclude, so that connection/setup problems surface
		 * immediately. Classification is deliberately left to precomputeInferences(), so that callers can report
		 * it as a separate step.
		 */
		final var reasoner = new ReusableKBReasoner(copy, configuration, releaseOnDispose, afterDispose);

		LOGGER.info(
				"Konclude initialized in {} ({} knowledge base).",
				formatDuration(startNs),
				reasoner.isReused() ? "reused" : "new"
		);

		return reasoner;
	}

	/**
	 * As Konclude does not support axioms of certain type, this method creates a copy of the given {@link OWLOntology}
	 * without unsupported axioms.
	 * <p>
	 * The copy is created in its own {@link org.semanticweb.owlapi.model.OWLOntologyManager}: the OWLlink client
	 * creates an ontology in the manager of the reasoned ontology for every response it parses and never removes it.
	 * This way, these ontologies are garbage collected together with the reasoner.
	 *
	 * @param ontology
	 *         the {@link OWLOntology} that should be copied
	 * @param additionalAxioms
	 *         axioms to add to the copy
	 *
	 * @return The copied {@link OWLOntology} as specified above
	 */
	private static OWLOntology createKoncludeFriendlyCopy(
			final OWLOntology ontology,
			final Collection<? extends OWLAxiom> additionalAxioms
	) {
		final OWLOntology koncludeCopy;

		try {
			koncludeCopy = OWLManager.createOWLOntologyManager().createOntology();
		} catch (final OWLOntologyCreationException e) {
			throw new RuntimeException(e);
		}

		final Collection<AxiomType<?>> occurringUnsupportedTypes = new HashSet<>();

		Stream.concat(ontology.logicalAxioms(Imports.INCLUDED), additionalAxioms.stream()).forEach(axiom -> {
			if (axiom.isOfType(UNSUPPORTED_TYPES)) {
				occurringUnsupportedTypes.add(axiom.getAxiomType());
			} else {
				koncludeCopy.add(axiom);
			}
		});

		if (!occurringUnsupportedTypes.isEmpty()) {
			LOGGER.info(
					"Konclude does not support axioms of type {}; their consequences will not be reflected by reasoning with Konclude.",
					occurringUnsupportedTypes.stream()
							.map(Objects::toString)
							.collect(Collectors.joining(", "))
			);
		}

		return koncludeCopy;
	}

	private static String formatDuration(final long startNs) {
		final long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

		if (elapsedMs < 1_000) {
			return elapsedMs + " ms";
		}

		return String.format("%.2f s", elapsedMs / 1_000.0);
	}
}