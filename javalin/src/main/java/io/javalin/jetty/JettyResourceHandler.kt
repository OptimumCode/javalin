/*
 * Javalin - https://javalin.io
 * Copyright 2017 David Åse
 * Licensed under Apache 2.0: https://github.com/tipsy/javalin/blob/master/LICENSE
 */

package io.javalin.jetty

import io.javalin.config.PrivateConfig
import io.javalin.http.Context
import io.javalin.http.staticfiles.Location
import io.javalin.http.staticfiles.StaticFileConfig
import io.javalin.util.JavalinException
import io.javalin.util.JavalinLogger
import io.javalin.util.javalinLazy
import jakarta.servlet.http.HttpServletRequest
import org.eclipse.jetty.ee10.servlet.ServletContextRequest
import org.eclipse.jetty.ee10.servlet.ServletContextResponse
import org.eclipse.jetty.http.MimeTypes
import org.eclipse.jetty.http.content.HttpContent
import org.eclipse.jetty.http.content.ResourceHttpContentFactory
import org.eclipse.jetty.io.EofException
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.ResourceService
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ResourceHandler
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.URIUtil
import org.eclipse.jetty.util.resource.Resource
import org.eclipse.jetty.util.resource.ResourceFactory
import java.nio.ByteBuffer
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.absolute
import io.javalin.http.staticfiles.ResourceHandler as JavalinResourceHandler

class JettyResourceHandler(val pvt: PrivateConfig) : JavalinResourceHandler {

    fun init() { // we delay the creation of ConfigurableHandler objects to get our logs in order during startup
        handlers.addAll(lateInitConfigs.map { ConfigurableHandler(it, pvt.jetty.server!!) })
    }

    private val lateInitConfigs = mutableListOf<StaticFileConfig>()
    private val handlers = mutableListOf<ConfigurableHandler>()

    override fun addStaticFileConfig(config: StaticFileConfig): Boolean =
        if (pvt.jetty.server?.isStarted == true) handlers.add(ConfigurableHandler(config, pvt.jetty.server!!)) else lateInitConfigs.add(config)

    override fun canHandle(ctx: Context) = nonSkippedHandlers(ctx.req()).any { handler ->
        try {
            fileOrWelcomeFile(handler, ctx.target) != null
        } catch (e: Exception) {
            e.message?.contains("Rejected alias reference") == true ||  // we want to say these are un-handleable (404)
                e.message?.contains("Failed alias check") == true // we want to say these are un-handleable (404)
        }
    }

    override fun handle(ctx: Context): Boolean {
        nonSkippedHandlers(ctx.req()).forEach { handler ->
            try {
                val target = ctx.target
                val fileOrWelcomeFile = fileOrWelcomeFile(handler, target)
                if (fileOrWelcomeFile != null) {
                    handler.config.headers.forEach { ctx.header(it.key, it.value) } // set user headers
                    return when (handler.config.precompress) {
                        true -> JettyPrecompressingResourceHandler.handle(target, fileOrWelcomeFile, ctx, pvt.compressionStrategy)
                        false -> {
                            ctx.res().contentType = null // Jetty will only set the content-type if it's null
                            runCatching { // we wrap the response to compress it with javalin's compression strategy
                                val request = ctx.jettyReq()
                                // TODO: should not be NOOP callback
                                // FIXME: wrapper does not work as expected
                                //        remove for now until figure out how to work with compression in this case
                                handler.handle(request, request.servletContextResponse, Callback.NOOP)
                            }.getOrDefault(false)
                        }
                    }
                }
            } catch (e: Exception) { // it's fine, we'll just 404
                if (e !is EofException) { // EofException is thrown when the client disconnects, which is fine
                    JavalinLogger.info("Exception occurred while handling static resource", e)
                }
            }
        }
        return false
    }

    private fun Resource?.fileOrNull(): Resource? = this?.takeIf { it.exists() && !it.isDirectory }
    private fun ResourceHandler.getResource(path: String): Resource? =
        // FIXME: the HttpContent returned by `getContent` should be released after usage I think
        httpContentFactory.getContent(path)?.resource
    private fun fileOrWelcomeFile(handler: ResourceHandler, target: String): Resource? =
        handler.getResource(target)?.fileOrNull() ?: handler.getResource("${target.removeSuffix("/")}/index.html")?.fileOrNull()

    private fun nonSkippedHandlers(request: HttpServletRequest) =
        handlers.asSequence().filter { !it.config.skipFileFunction(request) }

    private val Context.target get() = this.req().requestURI.removePrefix(this.req().contextPath)

}

open class ConfigurableHandler(val config: StaticFileConfig, jettyServer: Server) : ResourceHandler() {

    init {
        JavalinLogger.info("Static file handler added: ${config.refinedToString()}. File system location: '${getResourceBase(config)}'")
        baseResource = getResourceBase(config)
        isDirAllowed = false
        isEtags = true
        server = jettyServer
        start()
    }

    override fun newResourceService(): ResourceService {
        return object : ResourceService() {
            // WebJars are treated as an alias because of:
            // https://github.com/jetty/jetty.project/issues/12913
            override fun getContent(path: String, request: Request): HttpContent? {
                val aliasCheck = config.aliasCheck
                return if (aliasCheck != null) {
                    httpContentFactory.getContent(path)?.also { content ->
                        val resource = content.resource
                        if (resource.isAlias && !aliasCheck.checkAlias(path, resource)) {
                            throw AccessDeniedException("Failed alias check")
                        }
                    }
                } else {
                    super.getContent(path, request)
                }
            }
        }
    }

    override fun newHttpContentFactory(): HttpContent.Factory? {
        // MimeTypes are forcibly set in `doStart` method
        // But we need to update them before `newHttpContentFactory` is called in `doStart`
        // because of that update them here
        val mimeTypes = MimeTypes.Mutable(mimeTypes)
        config.mimeTypes.getMapping().forEach { (ext, mimeType) ->
            mimeTypes.addMimeMapping(ext, mimeType)
        }
        return object : ResourceHttpContentFactory(baseResource, mimeTypes) {
            override fun resolve(path: String): Resource? {
                return when {
                    config.directory == "META-INF/resources/webjars" ->
                        ResourceFactory.of(this@ConfigurableHandler)
                            .newClassLoaderResource("META-INF/resources$path", false)

                    config.hostedPath == "/" -> super.resolve(path) // same as regular ResourceHandler
                    path == config.hostedPath -> super.resolve("/")
                    path.startsWith(config.hostedPath + "/") -> super.resolve(path.removePrefix(config.hostedPath))
                    else -> null // files that don't start with hostedPath should not be accessible
                }
            }
        }
    }

    private fun getResourceBase(config: StaticFileConfig): Resource {
        val noSuchDirMessageBuilder: (String) -> String = { "Static resource directory with path: '$it' does not exist." }
        val classpathHint = "Depending on your setup, empty folders might not get copied to classpath."
        if (config.location == Location.CLASSPATH) {
            return ResourceFactory.of(this)
                .newClassLoaderResource(config.directory, false)
                ?: throw JavalinException("${noSuchDirMessageBuilder(config.directory)} $classpathHint")
        }

        // Use the absolute path as this aids in debugging. Issues frequently come from incorrect root directories, not incorrect relative paths.
        val absoluteDirectoryPath = Path(config.directory).absolute().normalize()
        if (!Files.exists(absoluteDirectoryPath)) {
            throw JavalinException(noSuchDirMessageBuilder(absoluteDirectoryPath.toString()))
        }
        return ResourceFactory.of(this).newResource(config.directory)
    }

}

private fun Context.jettyReq() = ServletContextRequest.getServletContextRequest(this.req())

private class CompressingResponseWrapper(private val ctx: Context) : Response.Wrapper(
    ServletContextRequest.getServletContextRequest(ctx.req()),
    ServletContextResponse.getServletContextResponse(ctx.res()),
) {
    override fun write(last: Boolean, byteBuffer: ByteBuffer, callback: Callback) {
        try {
            // FIXME: does not look like a good idea...
            // we make the non-blocking call block
            ctx.outputStream().write(byteBuffer.array(), byteBuffer.position(), byteBuffer.remaining())
            callback.succeeded()
        } catch (e: Exception) {
            callback.failed(e)
        }
    }
}
