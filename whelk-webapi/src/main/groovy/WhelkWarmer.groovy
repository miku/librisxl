package se.kb.libris.whelks.servlet

import groovy.util.logging.Slf4j as Log
import se.kb.libris.whelks.*
import se.kb.libris.whelks.exception.*

@Log
class WhelkWarmer extends org.restlet.ext.servlet.ServerServlet {
    void init() {
        super.init()
        def wi
        if (System.getProperty("whelk.config.uri")) {
            def wcu = System.getProperty("whelk.config.uri")
            def pcu = System.getProperty("plugin.config.uri", null)
            URI whelkconfig = new URI(wcu)
            log.info("Initializing whelks using definitions in $whelkconfig")
            if (pcu) {
                wi = new WhelkInitializer(whelkconfig.toURL().newInputStream(), new URI(pcu).toURL().newInputStream())
            } else {
                wi = new WhelkInitializer(whelkconfig.toURL().newInputStream(), null)
            }
        } else {
            // Use default config bundled with application.
            //wi = new WhelkInitializer(this.class.classLoader.getResourceAsStream("mock_whelks.json"))
            // Disabled. Should fail if no config is specified.
            throw new WhelkRuntimeException("Could not find suitable config. Please set the 'whelk.config.uri' system property")
        }
        def whelks = wi.getWhelks()
        log.info("Initiated $whelks")
        new javax.naming.InitialContext().rebind("whelks", whelks)
    }
}
