package org.example;

/**
 * A snapshot of the application's persistent settings, as stored in {@code application.yaml}.
 *
 * @param serverPort
 * 		The embedded web server's port ({@code server.port})
 * @param ontologyFile
 * 		The ontology file to load ({@code ontology.file})
 * @param koncludeOwlLinkServer
 * 		The OWLlink server URL Konclude is reached at ({@code reasoner.konclude.owllinkserver})
 * @param koncludeFolder
 * 		The folder the Konclude distribution is searched in ({@code reasoner.konclude.folder})
 * @param koncludeWorkers
 * 		The number of Konclude processing threads, or "AUTO" ({@code reasoner.konclude.workers})
 * @param koncludeKeepRunning
 * 		Whether Konclude is left running on exit ({@code reasoner.konclude.keep-running})
 * @param securityUsername
 * 		The login username ({@code spring.security.user.name})
 * @param securityPassword
 * 		The login password ({@code spring.security.user.password})
 * @param logLevelRoot
 * 		The root logging level ({@code logging.level.root})
 * @param logLevelSpringFramework
 * 		The {@code org.springframework} logging level ({@code logging.level.org.springframework})
 * @param logLevelSpringSecurityWeb
 * 		The {@code o.s.s.web} logging level ({@code logging.level.o.s.s.web})
 * @param logLevelApache
 * 		The {@code org.apache} logging level ({@code logging.level.org.apache})
 * @param logFilePath
 * 		The directory log files are written to ({@code logging.file.path})
 * @param theme
 * 		The UI's light/dark theme ({@code ui.theme}); not a Spring property, only read by this application
 */
record AppSettings(
        int serverPort,
        String ontologyFile,
        String koncludeOwlLinkServer,
        String koncludeFolder,
        String koncludeWorkers,
        boolean koncludeKeepRunning,
        String securityUsername,
        String securityPassword,
        String logLevelRoot,
        String logLevelSpringFramework,
        String logLevelSpringSecurityWeb,
        String logLevelApache,
        String logFilePath,
        Theme theme
) {

    enum Theme {
        LIGHT, DARK
    }
}
