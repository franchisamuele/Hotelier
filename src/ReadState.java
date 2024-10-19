import java.nio.ByteBuffer;

public class ReadState {
	
	public int count; // Numero totale di byte letti.
	public int length; // Dimensione del messaggio da ricevere.
	public ByteBuffer buffer; // Buffer per memorizzare il messaggio e la sua lunghezza.
	private int defaultBufSize; // Dimensione buffer per messaggi di lunghezza variabile.
	public int userId; // Tengo traccia dell'autenticazione.
	
	public ReadState(int bufSize) {
		this.count = 0;
		this.length = 0;
		this.buffer = ByteBuffer.allocate(bufSize);
		defaultBufSize = bufSize;
		userId = 0;
	}
	
	public void reset() {
		count = 0;
		length = 0;
		buffer = ByteBuffer.allocate(defaultBufSize);
	}
	
}
