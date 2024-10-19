import java.io.FileInputStream;
import java.io.IOException;
import java.lang.Exception;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.InputMismatchException;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ClientMain {
	
	private static final String configFile = "client.config";
	private static final String commonConfigFile = "server.config";
	
	private static String SERVER_IP;
	private static int PORT;
	private static int bufferSize;
	private static String exitMessage;
	private static String multicastAddress;
	private static int multicastPort;
	
	private static boolean authenticated = false;

	private static ByteBuffer buffer;
	private static Scanner in = new Scanner(System.in);
	
	private static BlockingQueue<String> notificationsQueue = new LinkedBlockingQueue<String>();
	
	public static void main(String[] args) {
		
		loadConfig();
		buffer = ByteBuffer.allocate(bufferSize);
		Thread notificationHandler = new NotificationHandler(bufferSize, multicastAddress, multicastPort, notificationsQueue);
		
		InetSocketAddress serverAddress = new InetSocketAddress(SERVER_IP, PORT);
		SocketChannel server = null;
		try {
			
		    while (server == null) {
		        try {
		        	// Apro la connessione con il server
		            server = SocketChannel.open(serverAddress);
		        } catch (ConnectException e) {
		        	// Riprovo ogni tot per vedere se il server torna raggiungibile
		            System.out.println("Server non raggiungibile, riprovo più tardi...");
		            Thread.sleep(5000);
		        }
		    }
			
		    // Faccio partire il thread che aspetta continuamente la ricezione di notifiche
			notificationHandler.start();
			
			int choice;
			while (true) {
				// Se ci sono notifiche in sospeso, le stampo
				while (!notificationsQueue.isEmpty())
					System.out.println("Notifica ricevuta: " + notificationsQueue.poll() + "\n");

				System.out.print("- Menu -\n"
						+ " 1) Registrati\n"
						+ " 2) " + (authenticated ? "Logout\n" : "Login\n")
						+ " 3) Cerca per nome Hotel\n"
						+ " 4) Cerca per città\n"
						+ (authenticated ? " 5) Lascia una recensione\n"
										+ " 6) Mostra il tuo badge\n" : "")
						+ " 0) Esci\n"
						+ "Scelta: ");
				choice = -1;
				
				try {
					choice = in.nextInt();
				} catch (InputMismatchException e) {
					// Inserisco qualcosa che non è un numero
					System.out.println("\nErrore: inserisci un numero valido.\n");
					continue;
				} finally {
					in.nextLine(); // Consume newline leftover
				}
				
				if (choice == 0) {
					handleExit(server);
					break;
				}
				
				if (choice < 1 || choice > 6 || (!authenticated && choice > 4)) {
					System.out.println("\nScelta non valida, riprova\n");
					continue;
				}
				
				System.out.println();
				
				switch (choice) {
					case 1:
						handleRegister(server);
					break;
					case 2:
						if (!authenticated)
							handleLogin(server);
						else
							handleLogout(server);
					break;
					case 3:
						handleSearchHotel(server);
					break;
					case 4:
						handleSearchAllHotels(server);
					break;
					case 5:
						handleInsertReview(server);
					break;
					case 6:
						handleShowBadge(server);
					break;
				}
			
				System.out.println();
			
			}
			
		} catch (Exception e) {
			System.out.println("\nTerminazione forzata...");
		} finally {
		
			System.out.println("\nArrivederci");

			// Chiudo gli oggetti aperti
			in.close();
			notificationHandler.interrupt();
			
			try {
				if (server != null)
					server.close();
				notificationHandler.join();
			} catch (Exception e) {}

		}
			
	}

	// Caricamento configurazioni dal file apposito
	private static void loadConfig() {
		Properties props = new Properties();
		
		try (
			FileInputStream fis = new FileInputStream(configFile);
			FileInputStream commonFis = new FileInputStream(commonConfigFile);
		) {
		    props.load(fis);
		    SERVER_IP = props.getProperty("SERVER_IP");
		    
		    props.load(commonFis);
		    PORT = Integer.parseInt(props.getProperty("PORT"));
		    bufferSize = Integer.parseInt(props.getProperty("bufferSize"));
		    exitMessage = props.getProperty("exitMessage");
		    multicastAddress = props.getProperty("multicastAddress");
		    multicastPort = Integer.parseInt( props.getProperty("multicastPort") );
		}  catch (IOException e) {
			System.out.println("Errore nella lettura del file di configurazione");
			System.exit(1);
		}
	}
	
	private static void handleRegister(SocketChannel server) throws IOException {
		System.out.println("- Registrazione -");
		System.out.print("Username: ");
		String username = in.nextLine().trim();
		System.out.print("Password: ");
		String password = in.nextLine().trim();
		
		// Costruisco la richiesta, la mando al server e aspetto la risposta
		String request = String.format("1;%s;%s", username, password);
		int response = sendToServerWithResponseCode(request, server);

		System.out.println();
		
		if (response == 0)
			System.out.println("Registrazione avvenuta con successo");
		else if (response == 1)
			System.out.println("Errore: Il campo Username è richiesto");
		else if (response == 2)
			System.out.println("Errore: Il campo Password è richiesto");
		else if (response == 3)
			System.out.println("Errore: Username già esistente");
	}
	
	private static void handleLogout(SocketChannel server) throws IOException {
		// Costruisco la richiesta, la mando al server e aspetto la risposta
		String request = "2";
		int response = sendToServerWithResponseCode(request, server);
		
		if (response == 0) {
			System.out.println("Logout effettuato con successo");
			authenticated = false;
		} else
			System.out.println("Errore nel logout");
	}
	
	private static void handleLogin(SocketChannel server) throws IOException {
		if (authenticated) {
			System.out.println("Errore: Login già effettuato");
			return;
		}
		
		System.out.println("- Login -");
		System.out.print("Username: ");
		String username = in.nextLine().trim();
		System.out.print("Password: ");
		String password = in.nextLine().trim();

		// Costruisco la richiesta, la mando al server e aspetto la risposta
		String request = String.format("2;%s;%s", username, password);
		int response = sendToServerWithResponseCode(request, server);
		
		System.out.println();
		
		if (response == 0) {
			authenticated = true;
			System.out.println("Buongiorno " + username + ", bentornato su HOTELIER");
		} else if (response == 1)
			System.out.println("Errore: Username e/o Password errati");
	}
	
	private static void handleSearchHotel(SocketChannel server) throws IOException {
		System.out.println("- Ricerca per hotel -");
		System.out.print("Nome Hotel: ");
		String hotelName = in.nextLine().trim();
		System.out.print("Città di riferimento: ");
		String cityName = in.nextLine().trim();

		// Costruisco la richiesta, la mando al server e aspetto la risposta
		String request = String.format("3;%s;%s", hotelName, cityName);
		String response = sendToServerWithResponseString(request, server);
		
		System.out.println();
		
		if (!response.equals("")) {
			System.out.println("- Hotel trovato -\n");
			System.out.println(response);
		} else
			System.out.println("- Hotel non trovato -");
	}
	
	private static void handleSearchAllHotels(SocketChannel server) throws IOException {
		System.out.println("- Ricerca per città -");
		System.out.print("Nome città: ");
		String cityName = in.nextLine().trim();

		// Costruisco la richiesta, la mando al server e aspetto la risposta
		String request = String.format("4;%s", cityName);
		String response = sendToServerWithResponseString(request, server);
		
		System.out.println();
		
		if (!response.equals("")) {
			System.out.println("- Hotel trovati a " + cityName + " -\n");
			System.out.println(response);
		} else
			System.out.println("- Nessun Hotel trovato a " + cityName + " -");
	}
	
	private static void handleInsertReview(SocketChannel server) throws IOException {
		System.out.println("- Recensisci hotel -");
		System.out.print("Nome Hotel: ");
		String hotelName = in.nextLine().trim();
		System.out.print("Città di riferimento: ");
		String cityName = in.nextLine().trim();
	    int rate = getScore("Valutazione globale (1-5): ");
	    int cleaningRate = getScore("Valutazione pulizia (1-5): ");
	    int positionRate = getScore("Valutazione posizione (1-5): ");
	    int servicesRate = getScore("Valutazione servizi (1-5): ");
	    int qualityRate = getScore("Valutazione prezzo (1-5): ");

		// Costruisco la richiesta, la mando al server e aspetto la risposta
		String request = String.format("5;%s;%s;%d;%d;%d;%d;%d", hotelName, cityName, rate, cleaningRate, positionRate, servicesRate, qualityRate);
		int responseCode = sendToServerWithResponseCode(request, server);
		
		System.out.println();
		
		if (responseCode == 0)
			System.out.println("Recensione inserita con successo");
		else if (responseCode == 1)
			System.out.println("Errore: Hotel non trovato");
		else if (responseCode == 2)
			System.out.println("Errore nella richiesta");
	}
	
	private static void handleShowBadge(SocketChannel server) throws IOException {
		// Costruisco la richiesta, la mando al server e aspetto la risposta
		String request = "6";
		String response = sendToServerWithResponseString(request, server);
		
		System.out.println("Il tuo livello di esperienza è: " + response);
	}
	
	private static void handleExit(SocketChannel server) throws IOException {
		sendToServerWithResponseCode(exitMessage, server);
	}

	// Costruisco il buffer con la richiesta, la invio al server e ricevo la risposta su quello stesso buffer
	private static int sendToServerWithResponseCode(String request, SocketChannel server) throws IOException {
		buffer.clear();
		buffer.putInt(request.length());
		buffer.put(request.getBytes());
		buffer.flip();
		server.write(buffer);
		
		// Se esco non aspetto la risposta
		if (request.equalsIgnoreCase(exitMessage))
			return 0;
		
		buffer.clear();
		server.read(buffer);
		buffer.flip();

		// Response code
		return buffer.getInt();
	}

	// Costruisco il buffer con la richiesta, la invio al server e ricevo la risposta su quello stesso buffer
	private static String sendToServerWithResponseString(String request, SocketChannel server) throws IOException {
	    buffer.clear();
	    buffer.putInt(request.length());
	    buffer.put(request.getBytes());
	    buffer.flip();
	    server.write(buffer);

	    buffer.clear();
	    int bytesRead = server.read(buffer);

	    buffer.flip();
	    int responseLength = buffer.getInt();
	    byte[] responseBytes = new byte[responseLength];
	    int totalBytesRead = bytesRead - Integer.BYTES; // Considero solo il messaggio effettivo
	    buffer.get(responseBytes, 0, totalBytesRead);

	    // Se il buffer non riesce a contenere tutto il messaggio di risposta, devo leggere più volte dal server
	    // e costruire incrementalmente la stringa ricevuta
	    while (totalBytesRead < responseLength) {
	        buffer.clear();
	        bytesRead = server.read(buffer);

	        if (bytesRead == -1) {
	            throw new IOException("Premature end of stream");
	        }

	        buffer.flip();
	        buffer.get(responseBytes, totalBytesRead, bytesRead);
	        totalBytesRead += bytesRead;
	    }

	    return new String(responseBytes);
	}

	// Leggo da tastiera i punteggi per la recensione, con appositi controlli
	private static int getScore(String message) {
		int score = 0;
		do {
			try {
				System.out.print(message);
				score = in.nextInt();
			} catch (InputMismatchException e) {
				// Inserisco qualcosa che non è un numero
				System.out.println("\nErrore: inserisci un numero valido.\n");
			} finally {
				in.nextLine(); // Consume newline leftover
			}
		} while (score < 1 || score > 5);

		return score;
	}

}
