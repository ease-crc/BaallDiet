package org.example;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URL;
import java.nio.file.Path;

/**
 * Class to represent the configurations of Konclude.
 *
 * @param owllinkserver
 * 		The {@link URL} where to find a running instance of Konclude as an OWLLinkServer
 * @param folder
 * 		The folder containing the Konclude distribution (or a folder containing it, such as the downloads folder).
 * 		Defaults to the downloads folder of the executing user.
 * @param keepRunning
 * 		Whether to leave a Konclude started by this application running on shutdown. As Konclude keeps its knowledge
 * 		bases, including their classification, the next start of this application can reuse them. Defaults to false.
 * @param workers
 * 		The number of Konclude processing threads, or "AUTO" to use all cores. Defaults to "AUTO".
 */
@ConfigurationProperties(prefix = "reasoner.konclude")
record KoncludeProperties(URL owllinkserver, Path folder, boolean keepRunning, String workers) {

	KoncludeProperties {
		if (folder == null) {
			folder = Path.of(System.getProperty("user.home"), "Downloads");
		}
		if (workers == null || workers.isBlank()) {
			workers = "AUTO";
		}
	}
}
