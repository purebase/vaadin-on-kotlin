package com.github.vok.example.crud

import com.github.vok.framework.VaadinOnKotlin
import com.github.vok.framework.entityManagerFactory
import org.atmosphere.util.annotation.AnnotationDetector
import org.eclipse.jetty.annotations.AnnotationConfiguration
import org.eclipse.jetty.plus.webapp.EnvConfiguration
import org.eclipse.jetty.plus.webapp.PlusConfiguration
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.webapp.*
import org.hibernate.jpa.AvailableSettings
import javax.persistence.Entity
import javax.persistence.Persistence


/**
 * Runs the testing Jetty-based server. Just open http://localhost:8080/ in your browser.
 */
fun main(args: Array<String>) {
    val server = Server(8080)
    val context = WebAppContext("src/main/webapp", "/")
    context.setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern", ".*/classes/.*")
    context.configurations = arrayOf<Configuration>(AnnotationConfiguration(), WebInfConfiguration(), WebXmlConfiguration(), MetaInfConfiguration(), FragmentConfiguration(), EnvConfiguration(), PlusConfiguration(), JettyWebXmlConfiguration())
    server.handler = context
    server.start()
    println("""
===========================================
Sample app running on http://localhost:8080
===========================================
""")
    server.join()
}
