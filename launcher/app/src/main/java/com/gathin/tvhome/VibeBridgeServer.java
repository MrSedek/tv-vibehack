package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ultra-lightweight local HTTP server running on port 8989.
 * Enables browser-to-TV integration:
 * - Web dashboard accessible from phone/PC at http://<tv-ip>:8989
 * - Instant video launch (YouTube -> SmartTube, streams -> Vimu/Player, magnet -> TorrServe)
 * - Video continuation sync (Watch Next)
 * - Virtual remote control
 */
public class VibeBridgeServer {

    private static final String TAG = "VibeBridge";
    public static final int PORT = 8989;

    private final Context ctx;
    private final Handler mainHandler;
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final OnWatchNextUpdatedListener watchNextListener;

    public interface OnWatchNextUpdatedListener {
        void onWatchNextAdded(WatchNextManager.WatchItem item);
    }

    public VibeBridgeServer(Context ctx, OnWatchNextUpdatedListener listener) {
        this.ctx = ctx.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.watchNextListener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        threadPool.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.i(TAG, "VibeBridge started on port " + PORT);
                while (running) {
                    Socket socket = serverSocket.accept();
                    threadPool.execute(() -> handleConnection(socket));
                }
            } catch (Exception e) {
                if (running) Log.e(TAG, "Server error: " + e.getMessage());
            }
        });
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
    }

    private void handleConnection(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {

            String requestLine = in.readLine();
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];

            // Read headers
            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            // Read body if POST
            StringBuilder body = new StringBuilder();
            if ("POST".equalsIgnoreCase(method) && contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = in.read(buf, 0, contentLength);
                if (read > 0) body.append(buf, 0, read);
            }

            if ("GET".equalsIgnoreCase(method) && "/".equals(path)) {
                sendHtmlResponse(out, getWebDashboardHtml());
            } else if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/status")) {
                sendJsonResponse(out, 200, getStatusJson());
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/play")) {
                handlePlayRequest(body.toString());
                sendJsonResponse(out, 200, "{\"success\":true}");
            } else if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/resume")) {
                handleResumeRequest(body.toString());
                sendJsonResponse(out, 200, "{\"success\":true}");
            } else {
                sendJsonResponse(out, 404, "{\"error\":\"not found\"}");
            }
        } catch (Exception e) {
            Log.w(TAG, "Connection handle failed: " + e.getMessage());
        }
    }

    private void handlePlayRequest(String bodyJson) {
        try {
            JSONObject obj = new JSONObject(bodyJson);
            String url = obj.optString("url", "").trim();
            long positionMs = obj.optLong("position_ms", 0);
            if (url.isEmpty()) return;

            mainHandler.post(() -> {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                // Smart intent routing
                if (url.contains("youtube.com") || url.contains("youtu.be")) {
                    // Try SmartTube first
                    intent.setPackage("com.liskovsoft.smarttubetv.beta");
                    if (positionMs > 0) intent.putExtra("time", (int) (positionMs / 1000));
                } else if (url.startsWith("magnet:") || url.endsWith(".torrent")) {
                    intent.setPackage("ru.yourok.torrserve");
                } else {
                    // Direct video stream
                    intent.setDataAndType(Uri.parse(url), "video/*");
                    if (positionMs > 0) intent.putExtra("position", positionMs);
                }

                try {
                    ctx.startActivity(intent);
                    Toast.makeText(ctx, "VibeBridge: Запуск видео", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    // Fallback without package restriction
                    intent.setPackage(null);
                    try {
                        ctx.startActivity(intent);
                    } catch (Exception ex) {
                        Toast.makeText(ctx, "Не найдено приложение для ссылки", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "handlePlay error: " + e.getMessage());
        }
    }

    private void handleResumeRequest(String bodyJson) {
        try {
            JSONObject obj = new JSONObject(bodyJson);
            String title = obj.optString("title", "Видео");
            String url = obj.optString("url", "");
            int progress = obj.optInt("progress", 50);
            String app = obj.optString("app", "Браузер");

            WatchNextManager.WatchItem item = new WatchNextManager.WatchItem(title, app, url, progress);
            WatchNextManager.addItem(ctx, item);

            mainHandler.post(() -> {
                if (watchNextListener != null) {
                    watchNextListener.onWatchNextAdded(item);
                }
                Toast.makeText(ctx, "Добавлено в «Продолжить просмотр»", Toast.LENGTH_SHORT).show();
            });
        } catch (Exception ignored) {
        }
    }

    private String getStatusJson() {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);
        long freeMb = mi.availMem / (1024 * 1024);
        long totalMb = mi.totalMem / (1024 * 1024);
        return String.format("{\"device\":\"Xiaomi Stick 4K\",\"ram_free_mb\":%d,\"ram_total_mb\":%d}", freeMb, totalMb);
    }

    private void sendJsonResponse(OutputStream out, int status, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private void sendHtmlResponse(OutputStream out, String html) throws Exception {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private String getWebDashboardHtml() {
        return "<!DOCTYPE html><html lang=\"ru\"><head><meta charset=\"UTF-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">" +
                "<title>VibeHome • Web Bridge</title>" +
                "<style>" +
                "*{box-sizing:border-box;margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;}" +
                "body{background:#000;color:#fff;padding:24px;display:flex;flex-direction:column;align-items:center;min-height:100vh;}" +
                ".card{background:rgba(20,20,24,0.85);backdrop-filter:blur(24px);border:1px solid rgba(255,255,255,0.15);" +
                "border-radius:20px;padding:24px;max-width:540px;width:100%;box-shadow:0 12px 40px rgba(0,0,0,0.8);margin-bottom:20px;}" +
                "h1{font-size:22px;font-weight:600;margin-bottom:6px;letter-spacing:-0.5px;}" +
                ".sub{font-size:13px;color:rgba(255,255,255,0.5);margin-bottom:20px;}" +
                "input,button{width:100%;border-radius:12px;padding:14px;border:none;font-size:15px;outline:none;margin-bottom:12px;}" +
                "input{background:rgba(255,255,255,0.08);color:#fff;border:1px solid rgba(255,255,255,0.12);}" +
                "input:focus{border-color:rgba(255,255,255,0.4);background:rgba(255,255,255,0.12);}" +
                "button{background:#fff;color:#000;font-weight:600;cursor:pointer;transition:transform 0.1s,opacity 0.2s;}" +
                "button:active{transform:scale(0.98);opacity:0.85;}" +
                ".badge{display:inline-block;padding:4px 10px;border-radius:20px;background:rgba(255,255,255,0.1);font-size:12px;margin-bottom:14px;}" +
                ".tip{font-size:12px;color:rgba(255,255,255,0.4);line-height:1.5;margin-top:10px;}" +
                "</style></head><body>" +
                "<div class=\"card\">" +
                "<div class=\"badge\">Xiaomi Stick 4K • Онлайн</div>" +
                "<h1>VibeHome Connect</h1>" +
                "<div class=\"sub\">Отправка видео и ссылок на ТВ в 1 клик</div>" +
                "<input id=\"urlInput\" type=\"text\" placeholder=\"Вставьте ссылку (YouTube, видеопоток, magnet)...\" />" +
                "<button onclick=\"sendPlay()\">▶ Запустить на ТВ</button>" +
                "<button onclick=\"sendResume()\" style=\"background:rgba(255,255,255,0.15);color:#fff;\">⏱ Добавить в «Продолжить просмотр»</button>" +
                "<div class=\"tip\">Поддерживает SmartTube, YouTube, TorrServe, Vimu Player, онлайн-потоки .m3u8 и mp4.</div>" +
                "</div>" +
                "<script>" +
                "function sendPlay(){" +
                "  const url = document.getElementById('urlInput').value.trim();" +
                "  if(!url) return alert('Введите ссылку');" +
                "  fetch('/api/play', {method:'POST', body:JSON.stringify({url})})" +
                "    .then(r=>r.json()).then(d=>alert('Отправлено на ТВ!'))" +
                "    .catch(e=>alert('Ошибка: '+e));" +
                "}" +
                "function sendResume(){" +
                "  const url = document.getElementById('urlInput').value.trim();" +
                "  if(!url) return alert('Введите ссылку');" +
                "  fetch('/api/resume', {method:'POST', body:JSON.stringify({title:'Видео из браузера', url, progress:35, app:'Web Browser'})})" +
                "    .then(r=>r.json()).then(d=>alert('Добавлено на полку «Продолжить просмотр» на ТВ!'))" +
                "    .catch(e=>alert('Ошибка: '+e));" +
                "}" +
                "</script></body></html>";
    }
}
