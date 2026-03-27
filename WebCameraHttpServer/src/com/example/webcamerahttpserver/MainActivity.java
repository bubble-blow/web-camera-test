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
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.HttpRequestHandlerRegistry;
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
    private volatile ServerSocket serverSocket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView statusText = (TextView) findViewById(R.id.statusText);
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

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }

        if (serverThread != null) {
            serverThread.interrupt();
        }
        super.onDestroy();
    }

    private void startHttpServer() {
        HttpParams params = new BasicHttpParams();
        params.setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 5000)
                .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
                .setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, false)
                .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true)
                .setParameter(CoreProtocolPNames.ORIGIN_SERVER, "AndroidAssetServer/1.0");

        BasicHttpProcessor processor = new BasicHttpProcessor();
        processor.addInterceptor(new ResponseDate());
        processor.addInterceptor(new ResponseServer());
        processor.addInterceptor(new ResponseContent());
        processor.addInterceptor(new ResponseConnControl());

        HttpService service = new HttpService(
                processor,
                new DefaultConnectionReuseStrategy(),
                new DefaultHttpResponseFactory());

        HttpRequestHandlerRegistry registry = new HttpRequestHandlerRegistry();
        registry.register("*", new AssetRequestHandler());
        service.setHandlerResolver(registry);
        service.setParams(params);

        try {
            serverSocket = new ServerSocket(PORT);
            while (running) {
                Socket socket = serverSocket.accept();
                DefaultHttpServerConnection conn = new DefaultHttpServerConnection();
                conn.bind(socket, params);
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
                    try {
                        conn.shutdown();
                    } catch (IOException ignored) {
                    }
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
            // Demo code: keep UI simple.
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
                ByteArrayEntity notFoundEntity = new ByteArrayEntity("404 Not Found".getBytes("UTF-8"));
                notFoundEntity.setContentType("text/plain; charset=UTF-8");
                response.setEntity(notFoundEntity);
                return;
            }

            response.setStatusCode(HttpStatus.SC_OK);
            ByteArrayEntity okEntity = new ByteArrayEntity(body);
            okEntity.setContentType(resolveMimeType(assetPath) + "; charset=UTF-8");
            response.setEntity(okEntity);
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
