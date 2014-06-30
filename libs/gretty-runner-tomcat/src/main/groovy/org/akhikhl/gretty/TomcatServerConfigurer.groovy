/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

import org.apache.catalina.connector.Connector
import org.apache.catalina.core.StandardContext
import org.apache.catalina.loader.WebappLoader
import org.apache.catalina.realm.MemoryRealm
import org.apache.catalina.startup.ContextConfig
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.startup.Tomcat.DefaultWebXmlListener
import org.apache.catalina.startup.Tomcat.FixContextListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 *
 * @author ahi
 */
class TomcatServerConfigurer {

  protected final Logger log

  TomcatServerConfigurer() {
    log = LoggerFactory.getLogger(this.getClass())
  }

  Tomcat createAndConfigureServer(TomcatConfigurer configurer, Map params, Closure configureContext = null) {

    Tomcat tomcat = new Tomcat()
    File tempDir = new File(System.getProperty('java.io.tmpdir'), 'tomcat-' + UUID.randomUUID().toString())
    new File(tempDir, 'webapps').mkdirs()
    tempDir.deleteOnExit()
    tomcat.setBaseDir(tempDir.absolutePath)

		tomcat.getHost().setAutoDeploy(true)
		tomcat.getEngine().setBackgroundProcessorDelay(-1)

    if(params.host)
      tomcat.setHostname(params.host)

    if(params.httpPort) {
      final Connector httpConn = new Connector('HTTP/1.1')
      httpConn.setScheme('http')
      httpConn.setPort(params.httpPort)
      httpConn.setProperty('maxPostSize', '0')  // unlimited
      if(params.httpIdleTimeout)
        httpConn.setProperty('keepAliveTimeout', params.httpIdleTimeout)
      if(params.httpsPort)
        httpConn.setRedirectPort(params.httpsPort)
      tomcat.getService().addConnector(httpConn)
      tomcat.setConnector(httpConn)
    }

    if(params.httpsPort) {
      final Connector httpsConn = new Connector('HTTP/1.1')
      httpsConn.setScheme('https')
      httpsConn.setPort(params.httpsPort)
      httpsConn.setProperty('maxPostSize', '0')  // unlimited
      httpsConn.setSecure(true)
      httpsConn.setProperty('SSLEnabled', 'true')
      if(params.sslKeyManagerPassword)
        httpsConn.setProperty('keyPass', params.sslKeyManagerPassword)
      if(params.sslKeyStorePath)
        httpsConn.setProperty('keystoreFile', params.sslKeyStorePath)
      if(params.sslKeyStorePassword)
        httpsConn.setProperty('keystorePass', params.sslKeyStorePassword)
      if(params.sslTrustStorePath)
        httpsConn.setProperty('truststoreFile', params.sslTrustStorePath)
      if(params.sslTrustStorePassword)
        httpsConn.setProperty('truststorePass', params.sslTrustStorePassword)
      if(params.httpsIdleTimeout)
        httpsConn.setProperty('keepAliveTimeout', params.httpsIdleTimeout)
      tomcat.getService().addConnector(httpsConn)
      if(!params.httpPort)
        tomcat.setConnector(httpsConn)
    }

    for(def webapp in params.webApps) {
      StandardContext context = params.contextClass ? params.contextClass.newInstance() : new StandardContext()
      context.setName(webapp.contextPath)
      context.setPath(webapp.contextPath)
      context.setDocBase(webapp.resourceBase)
      // context.setLogEffectiveWebXml(true) // enable for debugging webxml merge
      ClassLoader parentClassLoader = params.parentClassLoader ?: this.getClass().getClassLoader()
      URL[] classpathUrls = (webapp.webappClassPath ?: []).collect { new URL(it) } as URL[]
      ClassLoader classLoader = new URLClassLoader(classpathUrls, parentClassLoader)
      context.addLifecycleListener(new SpringloadedCleanup())
      context.setParentClassLoader(classLoader)
      context.setJarScanner(configurer.createJarScanner(context.getJarScanner(), new JarSkipPatterns()))
      WebappLoader loader = new WebappLoader(classLoader)
      loader.setLoaderClass(TomcatEmbeddedWebappClassLoader.class.getName())
      loader.setDelegate(true)
      context.setLoader(loader)

      context.addLifecycleListener(configurer.createContextConfig(classpathUrls))

      if(configureContext)
        configureContext(webapp, context)

      if(!context.findChild('default'))
        context.addLifecycleListener(new DefaultWebXmlListener())
        
      if(webapp.realmConfigFile) {
        if(new File(webapp.realmConfigFile).exists()) {
          log.warn 'Configuring security realm with config {}', webapp.realmConfigFile
          def realm = new MemoryRealm()
          realm.setPathname(webapp.realmConfigFile)
          context.setRealm(realm)
        }
      } else
        context.addLifecycleListener(new FixContextListener())

      tomcat.getHost().addChild(context)
    }

    tomcat
  }
}