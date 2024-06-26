package top.nicelee.purehost.vpn.server;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;


public class A0_PrintTCPContentServer implements Runnable{

	private static final String TAG = "A0_PrintTCPContentServer";

	@Override
	public void run() {
		ServerSocket serverSocket = null;
		Socket socket = null;
		Log.d(TAG,"服务器监听开始... ");
		try {
			serverSocket = new ServerSocket(7778);
			while (true) {
				socket = serverSocket.accept();
				Log.d(TAG,"收到新连接: " + socket.getInetAddress() + ":" + socket.getPort());
				BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

				String data;
				while( (data = reader.readLine() ) != null ) {
					//Log.d(TAG,"收到数据: ");
					Log.d(TAG,data);
					if(data.length() == 0)
						break;
				}
				
				
				String html = "<html><head><title>test</title></head><body><h1>测试</h1></body></html>";
				writer.write("HTTP/1.1 200 OK\r\n");
				writer.write("\r\nContent-Type: text/html; charset=UTF-8\r\n");
				writer.write("Content-Length: "+ html.length()+ "\r\n");
				writer.write("\r\n");
				writer.write(html);
				writer.write("\r\n");
				writer.flush();
				
				writer.close();
				reader.close();
				Log.d(TAG,"连接关闭");
			}
			
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
				}
			}
			if (serverSocket != null) {
				try {
					serverSocket.close();
				} catch (IOException e) {
				}
			}
		}
	}
}
