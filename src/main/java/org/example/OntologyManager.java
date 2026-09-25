package org.example;

import org.jspecify.annotations.NonNull;
import org.protege.xmlcatalog.owlapi.XMLCatalogIRIMapper;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.AnnotationValueShortFormProvider;
import org.semanticweb.owlapi.util.BidirectionalShortFormProviderAdapter;
import org.semanticweb.owlapi.util.DLExpressivityChecker;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * Class to manage the resources that are associated with an {@link OWLOntology}.
 */
@Component
@Lazy
public class OntologyManager {

	private static final Path XML_CATALOG_PATH = Path.of("catalog-v001.xml");

	/**
	 * {@link Logger} of this class.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(OntologyManager.class);

	/**
	 * {@link OWLOntologyManager} that holds the managed ontology.
	 */
	private final OWLOntologyManager ontologyManager = OWLManager.createConcurrentOWLOntologyManager();
	/**
	 * The managed {@link OWLOntology}.
	 */
	private final OWLOntology ontology;
	/**
	 * The file the managed ontology was loaded from and is saved to.
	 */
	private final File ontologyFile;
	/**
	 * The {@link BidirectionalShortFormProviderAdapter} that constructs short-forms based on the IRI only.
	 */
	private final BidirectionalShortFormProviderAdapter simpleShortFormProvider;

	/**
	 * The {@link BidirectionalShortFormProviderAdapter} that constructs short-forms based on the labels, if available,
	 * and otherwise based on the IRI.
	 */
	private final BidirectionalShortFormProviderAdapter labelShortFormProvider;

	/**
	 * Constructs a new {Manager}, that automatically loads the specified ontology upon construction.
	 *
	 * @param ontologyFile
	 * 		The file of the ontology that should be loaded and managed
	 *
	 * @throws OWLOntologyCreationException
	 * 		If there is an error loading the specified ontology
	 */
	public OntologyManager(@Value("${ontology.file}") final File ontologyFile)
			throws OWLOntologyCreationException, IOException {
		this.ontologyFile = ontologyFile;
		loadCatalog(ontologyFile.toPath().getParent());
		ontology = loadOntology(ontologyFile);

		// must be initialized in this exact same order
		simpleShortFormProvider = constructSimpleShortFormProvider();
		labelShortFormProvider = constructLabelShortFormProvider();
	}

	/**
	 * Constructs the simple {@link BidirectionalShortFormProviderAdapter}s of the
	 * managed ontology.
	 *
	 * @return the created simple {@link BidirectionalShortFormProviderAdapter}s of the
	 * * managed ontology
	 */
	private BidirectionalShortFormProviderAdapter constructSimpleShortFormProvider() {
		return new BidirectionalShortFormProviderAdapter(ontology.getImportsClosure(), new SimpleShortFormProvider());
	}

	private void loadCatalog(final Path ontologyDirectory) throws IOException {
		final var file = ontologyDirectory.resolve(XML_CATALOG_PATH).toFile();
		if (file.exists()) {
			final OWLOntologyIRIMapper xmlCatalogIriMapper = new XMLCatalogIRIMapper(file);
			ontologyManager.getIRIMappers().add(xmlCatalogIriMapper);
		}
	}

	/**
	 * Loads and returns the {@link OWLOntology} contained in the specified file
	 *
	 * @param ontologyFile
	 * 		The file of the {@link OWLOntology} to laod
	 *
	 * @return The loaded ontology
	 *
	 * @throws OWLOntologyCreationException
	 * 		If there occurs an error loading the {@link OWLOntology} from the given file
	 */
	private OWLOntology loadOntology(final File ontologyFile) throws OWLOntologyCreationException {
		LOGGER.info("Loading ontology with ontologyFile:{}", ontologyFile);
		final var loaded = ontologyManager.loadOntologyFromOntologyDocument(ontologyFile);
		LOGGER.info("Loaded '{}'", loaded.getOntologyID());
		final DLExpressivityChecker checker = new DLExpressivityChecker(loaded.getImportsClosure());
		LOGGER.info("Expressivity is {}",
		            checker.expressibleInLanguages().stream().filter(checker::minimal).findFirst().get().name());

		return loaded;
	}

	private static boolean isEnclosedBySingleQuotes(final String toCheck) {
		return toCheck.length() > 1 && toCheck.charAt(0) == '\'' && toCheck.charAt(toCheck.length() - 1) == '\'';
	}

	/**
	 * Constructs the label-using {@link BidirectionalShortFormProviderAdapter}s of the
	 * managed ontology.
	 *
	 * @return the created label-using {@link BidirectionalShortFormProviderAdapter}s of the
	 * * managed ontology
	 */
	private BidirectionalShortFormProviderAdapter constructLabelShortFormProvider() {
		final var rdfsLabel = OWLManager.getOWLDataFactory().getRDFSLabel();
		final var languagePreferences = Collections.singletonMap(rdfsLabel, Arrays.asList("en", ""));
		return new LabelShortFormProvider(rdfsLabel, languagePreferences);
	}

	/**
	 * @return The file the managed ontology was loaded from and is saved to
	 */
	public File getOntologyFile() {
		return ontologyFile;
	}

	/**
	 * Overwrites the ontology file with the managed ontology, in the format it was loaded in. The ontology is
	 * written to a temporary file first, so that a failure never leaves a half-written ontology file.
	 *
	 * @throws OWLOntologyStorageException
	 * 		If the ontology cannot be written
	 */
	public void saveOntology() throws OWLOntologyStorageException {
		final Path target = ontologyFile.toPath();
		final Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		final OWLDocumentFormat format = ontologyManager.getOntologyFormat(ontology);

		try {
			ontologyManager.saveOntology(
					ontology,
					format == null ? new FunctionalSyntaxDocumentFormat() : format,
					IRI.create(temporary.toFile())
			);
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (final IOException e) {
			throw new OWLOntologyStorageException(e);
		}
	}

	/**
	 * @return The managed ontology
	 */
	public OWLOntology getOntology() {
		return ontology;
	}

	/**
	 * @return The simple {@link BidirectionalShortFormProviderAdapter} of the managed ontology
	 */
	public BidirectionalShortFormProviderAdapter getSimpleShortFormProvider() {
		return simpleShortFormProvider;
	}

	/**
	 * @return The label-using {@link BidirectionalShortFormProviderAdapter} of the managed ontology
	 */
	public BidirectionalShortFormProviderAdapter getLabelShortFormProvider() {
		return labelShortFormProvider;
	}

	private class LabelShortFormProvider extends BidirectionalShortFormProviderAdapter {
		LabelShortFormProvider(final OWLAnnotationProperty rdfsLabel,
		                       final Map<OWLAnnotationProperty, List<String>> languagePreferences) {
			super(ontology.getImportsClosure(),
			      new AnnotationValueShortFormProvider(List.of(rdfsLabel), languagePreferences, ontologyManager));
		}

		@Override
		public Stream<OWLEntity> entities(final String shortForm) {
			Objects.requireNonNull(shortForm, "shortForm cannot be null");
			if (shortForm.length() < 2) {
				return Stream.empty();
			}
			return super.entities(shortForm.substring(1, shortForm.length() - 1));
		}
	}
}
