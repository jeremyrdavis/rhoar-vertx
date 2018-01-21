package com.redhat.labs.adjective;

import com.redhat.labs.adjective.services.AdjectiveService;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.api.contract.openapi3.OpenAPI3RouterFactory;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import java.sql.Connection;
import java.sql.DriverManager;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.vertx.ext.web.api.contract.openapi3.OpenAPI3RouterFactory.createRouterFactoryFromFile;

public class MainVerticle extends AbstractVerticle {

    AdjectiveService service;

    /**
     * Initialize and start the {@link MainVerticle}
     * @param startFuture An instance of {@link Future} which allows us to report on the startup status.
     */
    @Override
    public void start(Future<Void> startFuture) {
        // ConfigStore from Kube/OCPs
        Future<JsonObject> f1 = Future.future();
        this.initConfigRetriever(f1.completer())
                .compose(this::asyncLoadDbSchema)
                .compose(this::provisionRouter)
                .compose(f -> createHttpServer(startFuture, f), startFuture);
    }

    /**
     * Initialize the {@link ConfigRetriever}
     * @param handler Handles the results of requesting the configuration
     * @return A {@link Future} to be used in the next chained asynchronous operation
     */
    Future<JsonObject> initConfigRetriever(Handler<AsyncResult<JsonObject>> handler) {
        System.out.println("Retrieving configuration");
        Future<JsonObject> future = Future.future();
        JsonObject defaultConfig = new JsonObject()
                .put("http", new JsonObject()
                        .put("host", "0.0.0.0")
                        .put("port", 8080)
                )
                .put("db", new JsonObject()
                        .put("url", "jdbc:h2:mem:adjective;MODE=PostgreSQL")
                        .put("driver_class", "org.h2.Driver")
                        .put("user", "sa")
                        .put("password", ""));
        ConfigStoreOptions defaultOpts = new ConfigStoreOptions()
                .setType("json")
                .setConfig(defaultConfig);


        ConfigRetrieverOptions retrieverOptions = new ConfigRetrieverOptions()
                                            .addStore(defaultOpts);

        // Check to see if we are running on Kubernetes/OCP
        if (System.getenv().containsKey("KUBERNETES_NAMESPACE")) {

            ConfigStoreOptions confOpts = new ConfigStoreOptions()
                    .setType("configmap")
                    .setConfig(new JsonObject()
                            .put("name", "adjective_config")
                            .put("optional", true)
                    );
            retrieverOptions.addStore(confOpts);
        }

        ConfigRetriever.create(vertx, retrieverOptions).getConfig(future.completer());
        return future;
    }

    /**
     * Load the database schema from the config via Liquibase
     * @param config A {@link JsonObject} containing the configuration retrieved in the previous step.
     * @return A {@link Void} {@link Future} to be used to complete the next Async step
     */
    Future<Void> asyncLoadDbSchema(JsonObject config) {
        System.out.println("Storing retrieved configuration");
        vertx.getOrCreateContext().config().mergeIn(config);
        System.out.println("Initializing DB");
        final Future<Void> future = Future.future();
        vertx.executeBlocking(this::loadDbSchema, future.completer());
        return future;
    }

    /**
     * Synchronous method to use Liquibase to load the database schema
     * @param f A {@link Future} to be completed when operation is done
     */
    void loadDbSchema(Future<Void> f) {
        try {
            JsonObject dbCfg = vertx.getOrCreateContext().config().getJsonObject("db");
            Class.forName(dbCfg.getString("driver_class"));
            try (Connection conn = DriverManager.getConnection(
                    dbCfg.getString("url"),
                    dbCfg.getString("user"),
                    dbCfg.getString("password"))) {
                Database database = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(conn));
                Liquibase liquibase = new Liquibase("schema.xml", new ClassLoaderResourceAccessor(), database);
                liquibase.update(new Contexts(), new LabelExpression());
                f.complete();
            }
        } catch (Exception e) {
            f.fail(e);
        }
    }

    /**
     * Begin the creation of the {@link OpenAPI3RouterFactory}
     * @param v A Void for continuity in the async compoprovisionedsition
     * @return An {@link OpenAPI3RouterFactory} {@link Future} to be used to complete the next Async step
     */
    Future<OpenAPI3RouterFactory> provisionRouter(Void v) {
        System.out.println("Router provisioning");
        service = AdjectiveService.createProxy(vertx, "adjective.service");
        Future<OpenAPI3RouterFactory> future = Future.future();
        createRouterFactoryFromFile(vertx, "adjective.yml", future.completer());
        return future;
    }

    /**
     * Create an {@link HttpServer} and use the {@link OpenAPI3RouterFactory}
     * to handle HTTP requests
     * @param factory A {@link OpenAPI3RouterFactory} instance which is used to create a {@link Router}
     * @return The {@link HttpServer} instance created
     */
    void createHttpServer(Future startFuture, OpenAPI3RouterFactory factory) {
        System.out.println("Adding API handles");
        factory.addHandlerByOperationId("getAdjective", this::handleAdjGet);
        factory.addHandlerByOperationId("saveAdjective", this::handleAdjPost);
        factory.addHandlerByOperationId("health", this::healthCheck);
        System.out.println("Creating HTTP Server");
        JsonObject httpJsonCfg = vertx
                .getOrCreateContext()
                .config()
                .getJsonObject("http");
        HttpServerOptions httpConfig = new HttpServerOptions(httpJsonCfg);
        vertx.createHttpServer(httpConfig)
                .requestHandler(factory.getRouter()::accept)
                .listen(startFuture.completer());
    }

    void healthCheck(RoutingContext ctx) {
        service.check(res -> {
            if (res.succeeded()) {
                ctx.response()
                        .setStatusCode(CREATED.code())
                        .setStatusMessage(CREATED.reasonPhrase())
                        .end(res.result().encodePrettily());
            } else {
                ctx.response()
                        .setStatusCode(INTERNAL_SERVER_ERROR.code())
                        .setStatusMessage(INTERNAL_SERVER_ERROR.reasonPhrase())
                        .end();
            }
        });
    }

    void handleAdjPost(RoutingContext ctx) {
        service.get(res -> {
            if (res.succeeded()) {
                ctx.response()
                        .setStatusCode(CREATED.code())
                        .setStatusMessage(CREATED.reasonPhrase())
                        .end();
            } else {
                ctx.response()
                        .setStatusCode(INTERNAL_SERVER_ERROR.code())
                        .setStatusMessage(INTERNAL_SERVER_ERROR.reasonPhrase())
                        .end();
            }
        });
    }

    void handleAdjGet(RoutingContext ctx) {
        service.get(res -> {
            if (res.succeeded()) {
                ctx.response()
                        .setStatusCode(OK.code())
                        .setStatusMessage(OK.reasonPhrase())
                        .end(res.result().encodePrettily());
            } else {
                ctx.response()
                        .setStatusCode(INTERNAL_SERVER_ERROR.code())
                        .setStatusMessage(INTERNAL_SERVER_ERROR.reasonPhrase())
                        .end();
            }
        });
    }
}