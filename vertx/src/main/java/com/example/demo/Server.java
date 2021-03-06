package com.example.demo;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.SQLOptions;
import io.vertx.ext.sql.UpdateResult;
import io.vertx.ext.web.Router;

import java.util.Properties;
import java.util.concurrent.ExecutionException;

import static com.example.demo.Utils.clearExistingData;
import static com.example.demo.Utils.createJdbcClient;
import static com.example.demo.Utils.generateFibonocci;
import static com.example.demo.Utils.generateRandomMessage;
import static com.example.demo.Utils.loadProperties;

public class Server {

    // Vert.x logging wrapper is pretty much the same as Spring's, but uses JUL as the backend by default.
    // Which is unfortunate, as JUL kinda sucks (e.g. it doesn't support {}-style token replacement).  In a
    // real-world app, you'd probably take the time to replace this with Logback or some other backend.
    private final static Logger LOGGER = LoggerFactory.getLogger(Server.class);

    /**
     * The main entry point for the application.  This quick-and-dirty example uses {@link Vertx#vertx()} to
     * bootstrap Vert.x.  In a more real-world application, this class would instead be structured as a unit of
     * code called a "verticle", inheriting from {@link io.vertx.core.AbstractVerticle}, and launched via the
     * main class {@link io.vertx.core.Launcher}.
     */
    public static void main(final String[] args) throws ExecutionException, InterruptedException {
        final Properties properties = loadProperties();
        final Vertx vertx = Vertx.vertx(
            new VertxOptions().setWorkerPoolSize(Integer.parseInt(properties.getProperty("vertx.worker_pool_size"))));
        final JDBCClient jdbcClient = createJdbcClient(vertx, properties);

        // Purge any existing data from the MySQL table upon startup.  Really, this responsibility should probably
        // belong to the testing harness, since this method does nothing to purge generated by other framework
        // examples in the middle of a test suite run.  Regardless, it illustrates an example of forcing the
        // Vert.x Async JDBC client to operate in a blocking way.
        clearExistingData(jdbcClient);

        final HttpServer httpServer = vertx.createHttpServer();
        final Router router = Router.router(vertx);

        // Handler function for the "/noop" endpoint.  This is a dead-simple illustration of a non-blocking handler.
        router.route("/noop").handler(ctx -> ctx.response()
            .putHeader("content-type", "text/plain")
            .end("Hello world"));

        // Handler for the "/cpu" endpoint.  Because it performs blocking logic, it has to be created with the
        // "blockingHandler(...)" method rather than the "handler(...)" version.  Vert.x delegates this code off
        // to a thread from the worker pool, to prevent the HTTP event loop from blocking.
        //
        // Spring Boot 2.0 Reactive Web (i.e. Church's example) is much more performant for this endpoint.  Which
        // surprises me somewhat, since I thought that Spring was ultimately using more or less the same trick.  It
        // might be interesting to explore other ways of approaching this (e.g. Vert.x has support for integrating
        // RxJava).
        router.route("/cpu").blockingHandler(ctx -> {
            final int randomNum = (int) (Math.floor(Math.random() * 8) + 30);
            final long fibonocci = generateFibonocci(randomNum);
            ctx.response()
                .putHeader("content-type", "text/plain")
                .end(new StringBuilder("fib(").append(randomNum).append(") = ").append(fibonocci).toString());
        });

        // Handler for the "/sleep" endpoint.  See the notes for "/cpu" above.
        router.route("/sleep").blockingHandler(ctx -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            ctx.response()
                .putHeader("content-type", "text/plain")
                .end("I'm awake");
        });

        // Handler for the "/write" endpoint.  Because it uses Vert.x's non-blocking JDBC client, the web route
        // can be given a normal async handler.  This quick-and-dirty example chains together the async operations
        // for obtaining a JDBC connection and performing a write, but there are also other for mitigating the
        // "callback hell".
        router.route("/write").handler(routingCtx -> jdbcClient.getConnection(connectionCtx -> {
            if (connectionCtx.failed()) {
                routingCtx.response().setStatusCode(500).end();
            } else {
                final SQLConnection conn = connectionCtx.result();
                conn.setOptions(new SQLOptions().setAutoGeneratedKeys(true))
                    .updateWithParams("INSERT INTO t1 (message) VALUES (?)", new JsonArray().add(generateRandomMessage()), sqlCtx -> {
                        if (sqlCtx.failed()) {
                            conn.close();
                            routingCtx.response().setStatusCode(500).end();
                        } else {
                            final UpdateResult updateResult = sqlCtx.result();
                            conn.close();
                            routingCtx.response()
                                .putHeader("content-type", "text/plain")
                                .end("Added " + updateResult.getUpdated() + " rows, with ID(s): " + updateResult.getKeys().toString());
                        }
                    });
            }
        }));

        // Handler for the "/read" endpoint.  See the notes for "/write" above.
        router.route("/read").handler(routingCtx -> jdbcClient.getConnection(connectionCtx -> {
            if (connectionCtx.failed()) {
                routingCtx.response().setStatusCode(500).end();
            } else {
                final SQLConnection conn = connectionCtx.result();
                conn.query("SELECT message, COUNT(message) as 'count' FROM t1 GROUP BY message", sqlCtx -> {
                    if (sqlCtx.failed()) {
                        conn.close();
                        routingCtx.response().setStatusCode(500).end();
                    } else {
                        final ResultSet rs = sqlCtx.result();
                        conn.close();
                        routingCtx.response()
                            .putHeader("content-type", "text/plain")
                            .end(rs.getResults().toString());
                    }
                });
            }
        }));

        httpServer.requestHandler(router::accept).listen(Integer.parseInt(properties.getProperty("vertx.port")));
        LOGGER.info("HTTP server started on port " + properties.getProperty("vertx.port"));
    }

}
