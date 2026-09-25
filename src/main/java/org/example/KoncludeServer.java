package org.example;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Class to start a local Konclude OWLlink server on startup and to shut it down again on shutdown.
 * <p>
 * If {@link KoncludeProperties#keepRunning()} is set, Konclude is left running on shutdown, so that the knowledge bases
 * computed by Konclude can be reused by the next start (see {@link ReusableKBReasoner}). Such a Konclude is recognized
 * via a PID file and managed again by the next start. If there is a server listening on the configured port that was
 * not started by this application, no new process is started and the running server is left untouched.
 * <p>
 * In addition, {@link #startTemporary()} starts short-lived Konclude processes for one-off computations. Konclude does
 * not return the memory of released knowledge bases to the operating system, so terminating such a process is the only
 * way to free it.
 */
@Component
public class KoncludeServer {

	/**
	 * The {@link Logger} of this class.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(KoncludeServer.class);

	private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

	/**
	 * Candidate locations of the Konclude executable relative to the Konclude folder, in order of preference.
	 * The binaries are preferred over the wrapper scripts so that the started process is Konclude itself.
	 */
	private static final List<String> EXECUTABLE_CANDIDATES = IS_WINDOWS
			? List.of("Binaries/Konclude.exe", "Konclude.bat")
			: List.of("Binaries/Konclude", "Konclude.sh", "Konclude");

	/**
	 * How deep to search below the configured folder for a Konclude distribution.
	 */
	private static final int SEARCH_DEPTH = 3;

	private static final long STARTUP_TIMEOUT_MS = 30_000;

	private static final long SHUTDOWN_TIMEOUT_MS = 5_000;

	private final KoncludeProperties properties;

	private final File logFile;

	private final File temporaryLogFile;

	/**
	 * Records the Konclude process started by this application, so that a later start can recognize it (see
	 * {@link KoncludeProperties#keepRunning()}).
	 */
	private final Path pidFile;

	/**
	 * The Konclude process managed by this instance, or {@code null} if Konclude is not managed by this application,
	 * e.g., because it was started manually.
	 */
	private ProcessHandle konclude;

	/**
	 * The {@link Process} of {@link #konclude} if it was started by this instance, used to report its exit code.
	 */
	private Process process;

	/**
	 * The temporary Konclude processes that are still running. They are always terminated on shutdown.
	 */
	private final Set<Process> temporaryProcesses = new LinkedHashSet<>();

	/**
	 * A temporary Konclude process, which is terminated on {@link #close()}.
	 */
	public final class TemporaryKonclude implements AutoCloseable {

		private final Process temporaryProcess;
		private final URL url;

		private TemporaryKonclude(final Process temporaryProcess, final URL url) {
			this.temporaryProcess = temporaryProcess;
			this.url = url;
		}

		/**
		 * @return The URL of the OWLlink server of this process
		 */
		public URL url() {
			return url;
		}

		@Override
		public void close() {
			synchronized (KoncludeServer.this) {
				if (!temporaryProcesses.remove(temporaryProcess)) {
					return;
				}
			}

			LOGGER.info("Shutting down temporary Konclude (PID {}) to free its memory.", temporaryProcess.pid());
			terminate(temporaryProcess.toHandle());
		}
	}

	public KoncludeServer(
			final KoncludeProperties properties,
			@Value("${logging.file.path:log}") final String logDirectory
	) {
		this.properties = properties;
		this.logFile = Path.of(logDirectory, "konclude.log").toFile();
		this.temporaryLogFile = Path.of(logDirectory, "konclude-temporary.log").toFile();
		this.pidFile = Path.of(System.getProperty("java.io.tmpdir"), "bkb-script-konclude-" + getPort() + ".pid");
	}

	@PostConstruct
	private synchronized void start() throws IOException {
		final String host = properties.owllinkserver().getHost();
		final int port = getPort();

		/*
		 * Stop Konclude even if the Spring context cannot be closed properly, e.g., because a bean is still being
		 * created while the application exits.
		 */
		Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "konclude-shutdown"));

		if (isListening(host, port)) {
			konclude = readPidFile().orElse(null);

			if (konclude == null) {
				LOGGER.info("A server not started by this application is listening on {}:{}; not starting Konclude.",
				            host, port);
			} else {
				LOGGER.info("Using Konclude (PID {}) that was left running by a previous start.", konclude.pid());
			}
			return;
		}

		final Path executable = findExecutable().orElseThrow(() -> new IllegalStateException(
				"Could not find a Konclude executable in or below " + properties.folder().toAbsolutePath()
						+ ". Please set reasoner.konclude.folder in application.yaml."
		));

		LOGGER.info("Starting Konclude OWLlink server on port {} using {}.", port, executable);

		process = launch(executable, port, logFile);
		konclude = process.toHandle();
		writePidFile(konclude);

		LOGGER.info("Konclude started with PID {}; output is written to {}.", konclude.pid(), logFile);
	}

	/**
	 * Starts a temporary Konclude process on a free port and waits until it accepts connections.
	 *
	 * @return The started process, or empty if no Konclude executable was found
	 *
	 * @throws IllegalStateException
	 * 		If Konclude could not be started
	 */
	public Optional<TemporaryKonclude> startTemporary() {
		final Optional<Path> executable = findExecutable();

		if (executable.isEmpty()) {
			LOGGER.info("No Konclude executable found in or below {}; cannot start a temporary Konclude.",
			            properties.folder().toAbsolutePath());
			return Optional.empty();
		}

		final String host = properties.owllinkserver().getHost();
		final Process temporaryProcess;
		final int port;

		try {
			port = findFreePort();
			temporaryProcess = launch(executable.get(), port, temporaryLogFile);
		} catch (final IOException e) {
			throw new UncheckedIOException("Could not start a temporary Konclude.", e);
		}

		synchronized (this) {
			temporaryProcesses.add(temporaryProcess);
		}

		final TemporaryKonclude temporary;
		try {
			temporary = new TemporaryKonclude(temporaryProcess, toUrl(host, port));
		} catch (final MalformedURLException e) {
			terminate(temporaryProcess.toHandle());
			throw new IllegalStateException(e);
		}

		try {
			awaitListening(host, port, temporaryProcess, temporaryLogFile);
		} catch (final RuntimeException e) {
			temporary.close();
			throw e;
		}

		LOGGER.info("Temporary Konclude started with PID {} on port {}.", temporaryProcess.pid(), port);

		return Optional.of(temporary);
	}

	/**
	 * Blocks until the Konclude server accepts connections.
	 *
	 * @throws IllegalStateException
	 * 		If Konclude terminated or did not become ready in time
	 */
	public void awaitReady() {
		awaitListening(properties.owllinkserver().getHost(), getPort(), process, logFile);
	}

	@PreDestroy
	private synchronized void stop() {
		new ArrayList<>(temporaryProcesses).forEach(p -> {
			LOGGER.info("Shutting down temporary Konclude (PID {}).", p.pid());
			terminate(p.toHandle());
		});
		temporaryProcesses.clear();

		if (konclude == null) {
			return;
		}

		if (properties.keepRunning()) {
			LOGGER.info(
					"Leaving Konclude (PID {}) running for reuse by the next start (reasoner.konclude.keep-running).",
					konclude.pid()
			);
			konclude = null;
			return;
		}

		LOGGER.info("Shutting down Konclude (PID {}).", konclude.pid());
		terminate(konclude);
		konclude = null;

		try {
			Files.deleteIfExists(pidFile);
		} catch (final IOException e) {
			LOGGER.warn("Could not delete {}.", pidFile, e);
		}
	}

	private Process launch(final Path executable, final int port, final File log) throws IOException {
		if (!IS_WINDOWS && !Files.isExecutable(executable)) {
			// archives extracted on macOS/Linux do not necessarily preserve the executable bit
			executable.toFile().setExecutable(true);
		}

		final List<String> arguments = List.of("owllinkserver", "-p", String.valueOf(port), "-w", properties.workers());
		final List<String> command = new ArrayList<>();
		if (executable.toString().endsWith(".bat")) {
			command.addAll(List.of("cmd.exe", "/c"));
		}
		command.add(executable.toString());
		command.addAll(arguments);

		Files.createDirectories(log.toPath().toAbsolutePath().getParent());

		return new ProcessBuilder(command)
				.directory(executable.getParent().toFile())
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
				.start();
	}

	/**
	 * Blocks until a server accepts connections on the given port.
	 *
	 * @param serverProcess
	 * 		The process of the server, if started by this application, to detect its termination
	 *
	 * @throws IllegalStateException
	 * 		If the server process terminated or the server did not become ready in time
	 */
	private static void awaitListening(final String host, final int port, final Process serverProcess, final File log) {
		final long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;

		while (!isListening(host, port)) {
			if (serverProcess != null && !serverProcess.isAlive()) {
				throw new IllegalStateException(
						"Konclude terminated with exit code " + serverProcess.exitValue() + "; see " + log + ".");
			}
			if (System.currentTimeMillis() > deadline) {
				throw new IllegalStateException(
						"Konclude did not accept connections on port " + port + " within " + STARTUP_TIMEOUT_MS
								+ " ms; see " + log + ".");
			}
			try {
				Thread.sleep(100);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for Konclude.", e);
			}
		}
	}

	/**
	 * Terminates the given process and its descendants, forcibly if it does not terminate in time.
	 */
	private static void terminate(final ProcessHandle handle) {
		// destroy children as well, e.g., when started via a wrapper script
		final List<ProcessHandle> processes = new ArrayList<>(handle.descendants().toList());
		processes.add(handle);
		processes.forEach(ProcessHandle::destroy);

		try {
			handle.onExit().get(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (final ExecutionException | TimeoutException e) {
			// handled below
		}

		if (processes.stream().anyMatch(ProcessHandle::isAlive)) {
			LOGGER.warn("Konclude (PID {}) did not terminate in time; killing it.", handle.pid());
			processes.forEach(ProcessHandle::destroyForcibly);
		}
	}

	/**
	 * Writes the PID and start time of the given process, which together identify it even if the PID is reused.
	 */
	private void writePidFile(final ProcessHandle handle) {
		try {
			Files.writeString(pidFile, handle.pid() + " " + startInstant(handle).orElse(""));
		} catch (final IOException e) {
			LOGGER.warn("Could not write {}; a Konclude left running will not be recognized by the next start.",
			            pidFile, e);
		}
	}

	/**
	 * @return The still running Konclude process recorded in the PID file, if any
	 */
	private Optional<ProcessHandle> readPidFile() {
		try {
			if (!Files.isRegularFile(pidFile)) {
				return Optional.empty();
			}
			final String[] content = Files.readString(pidFile).trim().split(" ", 2);
			final String recordedStart = content.length > 1 ? content[1] : "";

			return ProcessHandle.of(Long.parseLong(content[0]))
					.filter(ProcessHandle::isAlive)
					.filter(handle -> startInstant(handle).orElse("").equals(recordedStart));
		} catch (final IOException | RuntimeException e) {
			LOGGER.warn("Could not read {}.", pidFile, e);
			return Optional.empty();
		}
	}

	private static Optional<String> startInstant(final ProcessHandle handle) {
		return handle.info().startInstant().map(Object::toString);
	}

	private int getPort() {
		final var url = properties.owllinkserver();
		return url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static URL toUrl(final String host, final int port) throws MalformedURLException {
		return URI.create("http://" + host + ":" + port).toURL();
	}

	/**
	 * Searches the configured folder, and folders named "Konclude*" below it, for the Konclude executable.
	 *
	 * @return The found executable, if any
	 */
	private Optional<Path> findExecutable() {
		final Path folder = properties.folder();

		final Optional<Path> direct = findExecutableIn(folder);
		if (direct.isPresent() || !Files.isDirectory(folder)) {
			return direct;
		}

		try (Stream<Path> directories = Files.find(
				folder,
				SEARCH_DEPTH,
				(path, attributes) -> attributes.isDirectory() && path.getFileName().toString().startsWith("Konclude")
		)) {
			return directories.sorted()
					.map(KoncludeServer::findExecutableIn)
					.flatMap(Optional::stream)
					.findFirst();
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Optional<Path> findExecutableIn(final Path folder) {
		return EXECUTABLE_CANDIDATES.stream()
				.map(folder::resolve)
				.filter(Files::isRegularFile)
				.findFirst();
	}

	private static boolean isListening(final String host, final int port) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), 200);
			return true;
		} catch (final IOException e) {
			return false;
		}
	}
}
