package org.example;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.owllink.OWLlinkHTTPXMLReasoner;
import org.semanticweb.owlapi.owllink.OWLlinkReasonerConfigurationImpl;
import org.semanticweb.owlapi.owllink.builtin.requests.CreateKB;
import org.semanticweb.owlapi.owllink.builtin.requests.GetAllClasses;
import org.semanticweb.owlapi.owllink.builtin.requests.ReleaseKB;
import org.semanticweb.owlapi.owllink.builtin.response.OWLlinkErrorResponseException;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link OWLlinkHTTPXMLReasoner} whose knowledge base on the OWLlink server is identified by a hash of the axioms of
 * the reasoned ontology.
 * <p>
 * If the server already holds a knowledge base for the same axioms, e.g., because Konclude kept running since the
 * last start of this application, that knowledge base is reused. Its axioms are not sent again and Konclude answers
 * from the classification it has already computed, instead of classifying the ontology from scratch.
 * <p>
 * Konclude keeps knowledge bases, which take a lot of memory, until they are released. A reasoner created with
 * {@code releaseOnDispose} releases its knowledge base when it is disposed, unless another reasoner of this application
 * still uses the same knowledge base. Note that Konclude does not return the memory of released knowledge bases to the
 * operating system (see {@link KoncludeServer#startTemporary()}).
 */
final class ReusableKBReasoner extends OWLlinkHTTPXMLReasoner {

	/**
	 * The {@link Logger} of this class.
	 */
	private static final Logger LOG = LoggerFactory.getLogger(ReusableKBReasoner.class);

	private static final String KB_NAMESPACE = "urn:bkb-script:kb:";

	/**
	 * The number of reasoners of this application that use a knowledge base. Also used as a lock, so that a knowledge
	 * base is not released while another reasoner starts to use it.
	 */
	private static final Map<IRI, Integer> KB_USAGES = new HashMap<>();

	/*
	 * Both fields are set while the super constructor runs (via createDefaultKB()). They deliberately have no
	 * initializer, as that would reset them after the super constructor returned.
	 */
	private boolean reused;
	private boolean skipInitialTell;

	/**
	 * Whether to release the knowledge base when this reasoner is disposed.
	 */
	private final boolean releaseOnDispose;

	/**
	 * Is run after this reasoner has been disposed.
	 */
	private final Runnable afterDispose;

	private boolean disposed;

	/**
	 * @param releaseOnDispose
	 * 		Whether to release the knowledge base when this reasoner is disposed
	 * @param afterDispose
	 * 		Is run after this reasoner has been disposed, e.g., to clean up resources the reasoner was created with
	 */
	ReusableKBReasoner(
			final OWLOntology ontology,
			final OWLlinkReasonerConfigurationImpl configuration,
			final boolean releaseOnDispose,
			final Runnable afterDispose
	) {
		super(ontology, configuration, BufferingMode.BUFFERING);
		this.releaseOnDispose = releaseOnDispose;
		this.afterDispose = afterDispose;
	}

	/**
	 * @return Whether an existing knowledge base on the OWLlink server was reused
	 */
	boolean isReused() {
		return reused;
	}

	@Override
	protected void createDefaultKB() {
		final IRI kb = IRI.create(KB_NAMESPACE + hashAxioms(getRootOntology()));
		defaultKnowledgeBase = kb;

		synchronized (KB_USAGES) {
			if (KB_USAGES.containsKey(kb)) {
				LOG.info("Reusing knowledge base {} that is already in use by this application.", kb);
				skipInitialTell = true;
				reused = true;
			} else {
				createOrReuseKB(kb);
			}
			KB_USAGES.merge(kb, 1, Integer::sum);
		}
	}

	private void createOrReuseKB(final IRI kb) {
		try {
			performRequest(new CreateKB(kb));
			LOG.info("Created knowledge base {} on the OWLlink server.", kb);
			return;
		} catch (final OWLlinkErrorResponseException e) {
			if (!String.valueOf(e.getMessage()).contains("already exists")) {
				throw e;
			}
		}

		if (containsSignatureOf(kb, getRootOntology())) {
			LOG.info("Reusing existing knowledge base {} on the OWLlink server.", kb);
			skipInitialTell = true;
			reused = true;
			return;
		}

		// e.g., the application was stopped while the axioms were being sent
		LOG.info("Existing knowledge base {} is incomplete; recreating it.", kb);
		performRequest(new ReleaseKB(kb));
		performRequest(new CreateKB(kb));
	}

	@Override
	public synchronized void dispose() {
		if (disposed) {
			return;
		}
		disposed = true;

		super.dispose();

		final IRI kb = getDefaultKB();
		final boolean unused;

		synchronized (KB_USAGES) {
			unused = KB_USAGES.merge(kb, -1, Integer::sum) <= 0;
			if (unused) {
				KB_USAGES.remove(kb);
			}

			if (releaseOnDispose && unused) {
				try {
					performRequest(new ReleaseKB(kb));
					LOG.info("Released knowledge base {} on the OWLlink server.", kb);
				} catch (final RuntimeException e) {
					LOG.warn("Could not release knowledge base {} on the OWLlink server.", kb, e);
				}
			}
		}

		afterDispose.run();
	}

	/**
	 * The super constructor sends all axioms returned by this method to the OWLlink server. For a reused knowledge
	 * base, that initial call returns no axioms, as the server already has them.
	 */
	@Override
	public Collection<OWLAxiom> getReasonerAxioms() {
		if (skipInitialTell) {
			skipInitialTell = false;
			return Set.of();
		}
		return super.getReasonerAxioms();
	}

	private boolean containsSignatureOf(final IRI kb, final OWLOntology ontology) {
		final Set<OWLClass> known = performRequest(new GetAllClasses(kb));
		return ontology.classesInSignature(Imports.INCLUDED)
				.filter(c -> !c.isOWLThing() && !c.isOWLNothing())
				.allMatch(known::contains);
	}

	private static String hashAxioms(final OWLOntology ontology) {
		final String axioms = ontology.axioms(Imports.INCLUDED)
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
