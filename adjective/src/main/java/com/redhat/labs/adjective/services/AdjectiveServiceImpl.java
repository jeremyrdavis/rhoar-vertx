package com.redhat.labs.adjective.services;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.serviceproxy.ServiceException;

public class AdjectiveServiceImpl implements AdjectiveService {

    SQLClient client;

    public AdjectiveServiceImpl(Vertx vertx) {
        JsonObject dbConfig = vertx.getOrCreateContext().config().getJsonObject("db");
        client = JDBCClient.createShared(vertx, dbConfig, "adjective");
    }

    @Override
    public void save(String adjective, Handler<AsyncResult<JsonObject>> resultHandler) {
        client.getConnection(connRes -> saveConnHandler(adjective, resultHandler, connRes));
    }

    private void saveConnHandler(String adjective, Handler<AsyncResult<JsonObject>> resultHandler, AsyncResult<SQLConnection> connRes) {
        if (connRes.succeeded()) {
            SQLConnection conn = connRes.result();
            JsonArray params = new JsonArray().add(adjective);
            conn.queryWithParams("INSERT INTO adjectives (adjective) VALUES (?)", params, queryRes -> {
                if (queryRes.succeeded()) {
                    JsonObject result = new JsonObject()
                            .put("url", String.format("/rest/v1/adjective/%s", adjective));
                    resultHandler.handle(Future.succeededFuture(result));
                } else {
                    resultHandler.handle(Future.failedFuture(queryRes.cause()));
                }
            });
        } else {
            resultHandler.handle(Future.failedFuture(connRes.cause()));
        }
    }

    @Override
    public void get(Handler<AsyncResult<JsonObject>> resultHandler) {
        client.getConnection(connRes -> handleGetConnectionResult(resultHandler, connRes));
    }

    private void handleGetConnectionResult(Handler<AsyncResult<JsonObject>> resultHandler, AsyncResult<SQLConnection> connRes) {
        if (connRes.succeeded()) {
            System.out.println("DB connection retrieved");
            SQLConnection conn = connRes.result();
            conn.query("SELECT adjective FROM adjectives ORDER BY RAND() LIMIT 1", queryRes -> {
                System.out.println("DB Query complete");
                if (queryRes.succeeded()) {
                    System.out.println("Got adjective from DB");
                    ResultSet resultSet = queryRes.result();
                    JsonObject result = resultSet.getRows().get(0);
                    resultHandler.handle(Future.succeededFuture(result));
                    connRes.result().close();
                } else {
                    System.out.println("Failed to get adjective from DB");
                    resultHandler.handle(Future.failedFuture(queryRes.cause()));
                }
            });
        } else {
            resultHandler.handle(Future.failedFuture(connRes.cause()));
        }
    }

    public void check(Handler<AsyncResult<JsonObject>> handler) {
        client.getConnection(connRes -> {
            if (connRes.succeeded()) {
                connRes.result().query("SELECT 1 FROM adjectives LIMIT 1", queryRes -> {
                    if (queryRes.succeeded()) {
                        handler.handle(Future.succeededFuture(new JsonObject().put("status", "OK")));
                        connRes.result().close();
                    } else {
                        handler.handle(Future.failedFuture(queryRes.cause()));
                    }
                });
            } else {
                handler.handle(Future.failedFuture(connRes.cause()));
            }
        });
    }
}