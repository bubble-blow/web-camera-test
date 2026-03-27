package com.example.webcamerahttpserver;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.DefaultBHttpServerConnection;
import org.apache.http.impl.DefaultBHttpServerConnectionFactory;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpProcessorBuilder;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.HttpRequestHandlerMapper;
import org.apache.http.protocol.HttpService;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.apache.http.util.EntityUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class MainActivity extends Activity {

    private static final int PORT = 8080;

    private Thread serverThread;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final TextView statusText = (TextView) findViewById(R.id.statusText);
        statusText.setText("HTTP server running at http://127.0.0.1:" + PORT + "/");

        running = true;
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                startHttpServer();
            }
        }, "asset-http-server");
        serverThread.start();
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (serverThread != null) {
            serverThread.interrupt();
        }
        super.onDestroy();
    }

    private void startHttpServer() {
        ServerSocket serverSocket = null;
        try {
            HttpProcessor processor = HttpProcessorBuilder.create()
                    .add(new ResponseDate())
                    .add(new ResponseServer("AndroidAssetServer/1.0"))
                    .add(new ResponseContent())
                    .add(new ResponseConnControl())
                    .build();

            HttpRequestHandlerMapper mapper = new HttpRequestHandlerMapper() {
                @Override
                public HttpRequestHandler lookup(HttpRequest request) {
                    return new AssetRequestHandler();
                }
            };

            HttpService service = new HttpService(processor, mapper);
            DefaultBHttpServerConnectionFactory connectionFactory = DefaultBHttpServerConnectionFactory.INSTANCE;

            serverSocket = new ServerSocket(PORT);
            while (running) {
                Socket socket = serverSocket.accept();
                DefaultBHttpServerConnection conn = connectionFactory.createConnection(socket);
                HttpContext context = new BasicHttpContext(null);
                try {
                    while (running && conn.isOpen()) {
                        service.handleRequest(conn, context);
                    }
                } catch (ConnectionClosedException ignored) {
                    // Client disconnected.
                } catch (HttpException ignored) {
                    // Malformed request.
                } finally {
                    conn.shutdown();
                    socket.close();
                }
            }
        } catch (IOException ignored) {
            // Demo code: keep UI simple.
        } finally {
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private class AssetRequestHandler implements HttpRequestHandler {
        @Override
        public void handle(HttpRequest request, HttpResponse response, HttpContext context)
                throws HttpException, IOException {
            String method = request.getRequestLine().getMethod().toUpperCase();
            if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) {
                throw new MethodNotSupportedException(method + " method not supported");
            }

            if (request instanceof HttpEntityEnclosingRequest) {
                HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
                EntityUtils.consume(entity);
            }

            String uri = request.getRequestLine().getUri();
            String assetPath = normalizePath(uri);
            byte[] body = readAsset(assetPath);

            if (body == null) {
                response.setStatusCode(HttpStatus.SC_NOT_FOUND);
                response.setEntity(new ByteArrayEntity("404 Not Found".getBytes("UTF-8"), ContentType.TEXT_PLAIN));
                return;
            }

            response.setStatusCode(HttpStatus.SC_OK);
            response.setEntity(new ByteArrayEntity(body, ContentType.create(resolveMimeType(assetPath), "UTF-8")));
        }

        private String normalizePath(String uri) {
            if (uri == null || "/".equals(uri)) {
                return "index.html";
            }
            String path = uri;
            int queryIndex = path.indexOf('?');
            if (queryIndex >= 0) {
                path = path.substring(0, queryIndex);
            }
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (path.length() == 0) {
                return "index.html";
            }
            return path;
        }

        private byte[] readAsset(String assetPath) throws IOException {
            InputStream input = null;
            ByteArrayOutputStream out = null;
            try {
                input = getAssets().open(assetPath);
                out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            } catch (IOException e) {
                return null;
            } finally {
                if (input != null) {
                    input.close();
                }
                if (out != null) {
                    out.close();
                }
            }
        }

        private String resolveMimeType(String assetPath) {
            if (assetPath.endsWith(".html")) {
                return "text/html";
            }
            if (assetPath.endsWith(".js")) {
                return "application/javascript";
            }
            if (assetPath.endsWith(".css")) {
                return "text/css";
            }
            if (assetPath.endsWith(".png")) {
                return "image/png";
            }
            if (assetPath.endsWith(".jpg") || assetPath.endsWith(".jpeg")) {
                return "image/jpeg";
            }
            return "application/octet-stream";
        }
    }
}
