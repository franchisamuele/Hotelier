import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.util.concurrent.BlockingQueue;

public class NotificationHandler extends Thread {

	private int bufferSize;
	private String multicastAddress;
	private int multicastPort;
	private InetAddress group;
	private MulticastSocket socket;
	private BlockingQueue<String> notificationsQueue;

	public NotificationHandler(int bufferSize, String multicastAddress, int multicastPort, BlockingQueue<String> notificationsQueue) {
		this.bufferSize = bufferSize;
		this.multicastAddress = multicastAddress;
		this.multicastPort = multicastPort;
		this.socket = null;
		this.notificationsQueue = notificationsQueue;
	}

	@SuppressWarnings("deprecation")
	@Override
	public void run() {
		try {
			socket = new MulticastSocket(multicastPort);
			socket.setReuseAddress(true); // se apro più client sulla stessa macchina
			group = InetAddress.getByName(multicastAddress);

			socket.joinGroup(group); // entro nel gruppo molticast
			byte[] buf = new byte[bufferSize];
			DatagramPacket dp = new DatagramPacket(buf, buf.length);

			while (true) {
				socket.receive(dp);
				notificationsQueue.add(new String(dp.getData(), 0, dp.getLength()));
			}
			
		} catch (SocketException e) {

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@SuppressWarnings("deprecation")
	@Override
	public void interrupt() {
		super.interrupt();
		
		if (socket == null)
			return;
		
		try {
			socket.leaveGroup(group);
		} catch (IOException e) {
			// Not a member of a group
		}
		
		socket.close();
	}

}
