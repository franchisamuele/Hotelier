import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class ServerMain {

	private static final Gson gson = new GsonBuilder().setPrettyPrinting().setDateFormat("dd-MM-yyyy").create();
	private static final String configFile = "server.config";

	private static int PORT;
	private static int bufferSize;
	private static String exitMessage;
	private static String hotelsFile;
	private static String usersFile;
	private static String reviewsFile;
	private static String multicastAddress;
	private static int multicastPort;
	private static int autosaveRate;

	private static List<Hotel> hotels;
	private static List<User> users;
	private static List<Review> reviews;

	// Thread safe, in vista di un possibile threadpool
	private static final Set<String> cities = ConcurrentHashMap.newKeySet();
	private static final Map<String, List<Hotel>> rankings = new ConcurrentHashMap<String, List<Hotel>>();

	private static MulticastSocket notificationSocket;
	private static InetAddress multicastGroup;

	private static final HotelComparator comparator = new HotelComparator();

	private static boolean autoSaverRunning = true;

	public static void main(String[] args) {

		loadConfig();
		// Carico i dati dai file JSON
		hotels = new Vector<Hotel>( Arrays.asList( jsonToObjectArray(hotelsFile, Hotel[].class ) ) );
		users = new Vector<User>( Arrays.asList( jsonToObjectArray(usersFile, User[].class ) ) );
		reviews = new Vector<Review>( Arrays.asList( jsonToObjectArray(reviewsFile, Review[].class ) ) );

		// Carico il nome delle città capoluogo, per poter fare un ranking per ognuna di esse
		// In lowercase per gestire le richieste in maniera case insensitive
		for (Hotel hotel : hotels)
			cities.add(hotel.city.toLowerCase());

		// Calcolo i ranking iniziali
		updateRankings();

		// Sincronizzo l'incremento progressivo degli id con quelli del file JSON
		if (!users.isEmpty())
			User.PROGRESSIVE_ID = users.get(users.size() - 1).getId() + 1;

		// Aggiornamenti periodici
		Thread autoSaver = new Thread(new Runnable() {
			@Override
			public void run() {
				while (autoSaverRunning) {
					try {
						Thread.sleep(autosaveRate);
						updateRankings();
						// Eventuali salvataggi su JSON
						// In questo caso gestiti ad ogni operazione
					} catch (InterruptedException e) {
					}
				}
			}
		});
		autoSaver.start();

		// Salvataggio finale prima di chiudere il server, per garantire consistenza
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				System.out.println("[SERVER] Terminazione server...");

				// In questo caso inutile poiché garantisco la persistenza ad ogni operazione
				objectArrayToJson(hotels.toArray(), hotelsFile);
				objectArrayToJson(users.toArray(), usersFile);
				objectArrayToJson(reviews.toArray(), reviewsFile);

				try {
					autoSaverRunning = false;
					autoSaver.join();
				} catch (InterruptedException e) {
				}
			}
		});

		try (
			ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
			Selector selector = Selector.open();
		) {

			serverSocketChannel.bind( new InetSocketAddress(PORT) );
			serverSocketChannel.configureBlocking(false);
			serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

			System.out.println("[SERVER] In ascolto su porta " + PORT);

			notificationSocket = new MulticastSocket();
			multicastGroup = InetAddress.getByName(multicastAddress);

			while (true) {
				selector.select();

				Iterator<SelectionKey> readyKeys = selector.selectedKeys().iterator();

				while (readyKeys.hasNext()) {
					SelectionKey key = readyKeys.next();
					readyKeys.remove();

					if (key.isAcceptable())
						handleAccept(selector, key);

					else if (key.isReadable())
						handleRead(selector, key);

					else if (key.isWritable())
						handleWrite(selector, key);
				}
			}

		} catch (BindException e) {
			System.out.println("[SERVER] Errore: porta " + PORT + " già in uso");
			System.exit(3);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(3);
		}

	}

	// Caricamento configurazioni dal file apposito
	private static void loadConfig() {
		Properties props = new Properties();
		try (FileInputStream fis = new FileInputStream(configFile)) {
			props.load(fis);
			PORT = Integer.parseInt(props.getProperty("PORT"));
			bufferSize = Integer.parseInt(props.getProperty("bufferSize"));
			exitMessage = props.getProperty("exitMessage");
			hotelsFile = props.getProperty("hotelsFile");
			usersFile = props.getProperty("usersFile");
			reviewsFile = props.getProperty("reviewsFile");
			multicastAddress = props.getProperty("multicastAddress");
			multicastPort = Integer.parseInt(props.getProperty("multicastPort"));
			autosaveRate = Integer.parseInt(props.getProperty("autosaveRate"));
		} catch (IOException e) {
			System.out.println("Errore nella lettura del file di configurazione");
			System.exit(1);
		}
	}

	// Parsing da file JSON a oggetti Java
	private static <T> T[] jsonToObjectArray(String fileName, Class<T[]> elementType) {
		try (FileReader reader = new FileReader(fileName)) {
			return gson.fromJson(reader, elementType);
		} catch (IOException e) {
			System.out.println("Errore nell'apertura del file '" + fileName + "'");
			System.exit(2);
		}
		return null;
	}

	// Parsing da oggetti Java a file JSON
	private static <T> void objectArrayToJson(T[] objArray, String fileName) {
		try (FileWriter writer = new FileWriter(fileName)) {
			gson.toJson(objArray, writer);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// Connessione con nuovo client
	private static void handleAccept(Selector selector, SelectionKey key) throws IOException {
		ServerSocketChannel server = (ServerSocketChannel) key.channel();
		SocketChannel client = server.accept();
		System.out.println("[SERVER] Nuova connessione ricevuta");
		client.configureBlocking(false);
		// Mi preparo a leggere dal client
		client.register(selector, SelectionKey.OP_READ, new ReadState(bufferSize));
	}

	// Lettura da client
	private static void handleRead(Selector selector, SelectionKey key) throws IOException {
		SocketChannel client = (SocketChannel) key.channel();
		ReadState state = (ReadState) key.attachment();

		try {

			state.count += client.read(state.buffer);
			System.out.println("[SERVER] Lettura da client");

		} catch (SocketException e) {
			System.out.println("[SERVER] Client disconnesso in maniera anomala");
			client.close(); // Cancella anche la chiave.
			return;
		}

		// Devo ancora leggere la lunghezza del messaggio
		if (state.count < Integer.BYTES)
			return;

		// Non ho ancora inserito la lunghezza del messaggio
		if (state.length == 0) {
			state.buffer.flip();
			state.length = state.buffer.getInt();
			state.buffer.compact();
		}

		// Non ho ancora letto tutto il messaggio
		if (state.count < Integer.BYTES + state.length)
			return;

		// Costruisco la stringa della richiesta ricevuta
		state.buffer.flip();
		byte[] requestBytes = new byte[state.length];
		state.buffer.get(requestBytes);
		String request = new String(requestBytes);

		System.out.println("[SERVER] Richiesta ricevuta: " + request);

		// Richiesta di terminazione
		if (request.equalsIgnoreCase(exitMessage)) {
			System.out.println("[SERVER] Client disconnesso");
			client.close(); // Cancella anche la chiave.
			return;
		}

		// Preparo la risposta per il client
		handleResponse(request, state);
		// Mi preparo a scrivere sul client
		client.register(selector, SelectionKey.OP_WRITE, state);
	}

	private static void handleResponse(String request, ReadState state) {
		String requestParameters[] = request.split(";");

		// Richiesta vuota
		if (requestParameters.length == 0)
			return;

		int requestCode = Integer.parseInt(requestParameters[0]);

		// Gestisco la richiesta opportunamente e preparo il buffer di risposta
		switch (requestCode) {
			// Register
			case 1: {
				int responseCode = register(requestParameters, state);
				state.buffer = ByteBuffer.allocate(Integer.BYTES);
				state.buffer.putInt(responseCode);
			}
			break;
			// Login o Logout
			case 2: {
				int responseCode;
				if (state.userId != 0) { // Già loggato
					responseCode = logout(state);
				} else { // Non loggato
					responseCode = login(requestParameters, state);
				}
				state.buffer = ByteBuffer.allocate(Integer.BYTES);
				state.buffer.putInt(responseCode);
			}
			break;
			// Cerca hotel
			case 3: {
				String response = searchHotel(requestParameters);
				byte responseBytes[] = response.getBytes();
				state.buffer = ByteBuffer.allocate(Integer.BYTES + responseBytes.length);
				state.buffer.putInt(responseBytes.length).put(responseBytes);
			}
			break;
			// Cerca tutti gli hotel in una città
			case 4: {
				String response = searchAllHotels(requestParameters);
				byte responseBytes[] = response.getBytes();
				state.buffer = ByteBuffer.allocate(Integer.BYTES + responseBytes.length);
				state.buffer.putInt(responseBytes.length).put(responseBytes);
			}
			break;
			// Inserisci recensione
			case 5: {
				int responseCode = insertReview(state.userId, requestParameters);
				state.buffer = ByteBuffer.allocate(Integer.BYTES);
				state.buffer.putInt(responseCode);
			}
			break;
			// Mostra badge
			case 6: {
				String response = showMyBadge(state);
				byte responseBytes[] = response.getBytes();
				state.buffer = ByteBuffer.allocate(Integer.BYTES + responseBytes.length);
				state.buffer.putInt(responseBytes.length).put(responseBytes);
			}
			break;
		}

		// Mi preparo alla lettura dal buffer
		state.buffer.flip();
	}

	// Scrittura sul client
	private static void handleWrite(Selector selector, SelectionKey key) throws IOException {
		SocketChannel client = (SocketChannel) key.channel();
		ReadState state = (ReadState) key.attachment();

		client.write(state.buffer);
		System.out.println("[SERVER] Scrittura su client");

		// Devo finire di scrivere sul client
		if (state.buffer.hasRemaining())
			return;

		// Mi preparo alla lettura di una nuova richiesta
		state.reset();
		client.register(selector, SelectionKey.OP_READ, state);
	}

	private static int register(String requestParameters[], ReadState state) {
		if (requestParameters.length < 2)
			return 1;

		if (requestParameters.length < 3)
			return 2;

		String username = requestParameters[1];
		String password = requestParameters[2];

		// Username richiesto
		if (username == "")
			return 1;

		// Password richiesta
		if (password == "")
			return 2;

		// Username già esistente
		if (users.stream().anyMatch(u -> u.getUsername().equals(username)))
			return 3;

		// Creo il nuovo utente e lo persisto sia sul server in esecuzione che sul file JSON
		User newUser = new User(username, password);
		users.add(newUser);
		objectArrayToJson(users.toArray(), usersFile);
		return 0;
	}

	private static int login(String requestParameters[], ReadState state) {
		if (requestParameters.length < 3)
			return 1;

		String username = requestParameters[1];
		String password = requestParameters[2];

		// Username e Password richieste
		if (username == "" || password == "")
			return 1;

		List<User> usersFound = users.stream()
				.filter(u -> u.getUsername().equals(username) && u.getPassword().equals(password)).toList();

		// Username o Password errate
		if (usersFound.size() == 0)
			return 1;

		// Tengo traccia dell'utente autenticato nell'attachment
		state.userId = usersFound.get(0).getId();
		return 0;
	}

	private static int logout(ReadState state) {
		state.userId = 0;
		return 0;
	}

	private static String searchHotel(String requestParameters[]) {
		if (requestParameters.length < 3)
			return "";

		String hotelName = requestParameters[1];
		String cityName = requestParameters[2];

		for (Hotel hotel : hotels) {
			if (hotel.name.equalsIgnoreCase(hotelName) && hotel.city.equalsIgnoreCase(cityName))
				return gson.toJson(hotel); // Ritorno il primo hotel trovato
		}

		// Nessun hotel trovato
		return "";
	}

	private static String searchAllHotels(String requestParameters[]) {
		if (requestParameters.length < 2)
			return "";

		String cityName = requestParameters[1];

		List<Hotel> filteredHotels = rankings.get(cityName.toLowerCase());

		// Nessun hotel trovato
		if (filteredHotels == null || filteredHotels.size() == 0)
			return "";

		// Ritorno gli hotel ordinati per ranking
		return gson.toJson(filteredHotels);
	}

	private static int insertReview(int userId, String requestParameters[]) {
		if (requestParameters.length < 8)
			return 2;

		List<User> usersFound = users.stream().filter(u -> u.getId() == userId).toList();

		// L'id dell'utente non esiste
		if (usersFound.isEmpty())
			return 2;

		User user = usersFound.get(0);

		String hotelName = requestParameters[1];
		String cityName = requestParameters[2];
		int rate = Integer.parseInt(requestParameters[3]);
		int cleaningRate = Integer.parseInt(requestParameters[4]);
		int positionRate = Integer.parseInt(requestParameters[5]);
		int servicesRate = Integer.parseInt(requestParameters[6]);
		int qualityRate = Integer.parseInt(requestParameters[7]);
		
		// Controlla che tutti i rate siano nel range 1-5, in caso contrario restituisce un codice di errore
		if (checkRates(rate, cleaningRate, positionRate, servicesRate, qualityRate) == false)
			return 2;

		for (Hotel hotel : hotels) {
			// Trovo l'hotel che devo recensire
			if (hotel.name.equalsIgnoreCase(hotelName) && hotel.city.equalsIgnoreCase(cityName)) {
				// Aggiungo la recensione con i parametri passati
				Review review = new Review(userId, hotel.id(), rate, new Rating(cleaningRate, positionRate, servicesRate, qualityRate), new Date());
				reviews.add(review);
				// Aggiorno le medie per il successivo calcolo dei ranking
				hotel.newReview(review);
				// Aggiorno il numero di recensioni per il calcolo del badge
				user.addReview();

				// Persisto le informazioni sui file JSON
				objectArrayToJson(reviews.toArray(), reviewsFile);
				objectArrayToJson(hotels.toArray(), hotelsFile);
				objectArrayToJson(users.toArray(), usersFile);
				return 0;
			}
		}

		return 1;
	}
	
	// Controlla che tutti i rate siano nel range corretto. In caso contrario ritorna false
	private static boolean checkRates(int rate, int cleaningRate, int positionRate, int servicesRate, int qualityRate) {
		if (rate < 1 || rate > 5)
			return false;
		if (cleaningRate < 1 || cleaningRate > 5)
			return false;
		if (positionRate < 1 || positionRate > 5)
			return false;
		if (servicesRate < 1 || servicesRate > 5)
			return false;
		if (qualityRate < 1 || qualityRate > 5)
			return false;
			
		return true;
	}

	private static String showMyBadge(ReadState state) {
		List<User> usersFound = users.stream().filter(u -> u.getId() == state.userId).toList();

		// Utente non trovato
		if (usersFound.size() == 0)
			return "Errore";

		// Ritorno la stringa che rappresenta il badge
		return usersFound.get(0).getBadge();
	}

	private static void updateRankings() {
		for (String city : cities) {
			List<Hotel> oldCityRanking = rankings.get(city);

			// Calcolo il nuovo ranking ordinando usando la classe Comparable
			List<Hotel> newCityRanking = hotels.stream()
					.filter(h -> h.city.equalsIgnoreCase(city))
					.sorted(comparator)
					.toList();

			// Aggiorno il ranking associato a quella città
			rankings.put(city, newCityRanking);

			// Se non ci sono hotel associati a quella città
			if (oldCityRanking == null || oldCityRanking.isEmpty() || newCityRanking == null || newCityRanking.isEmpty())
				continue;

			Hotel oldFirstPlace = oldCityRanking.get(0);
			Hotel newFirstPlace = newCityRanking.get(0);

			// Se è cambiato il primo posto, notifico tutti i client
			if (newFirstPlace.id() != oldFirstPlace.id())
				notifyAll(newFirstPlace.city, newFirstPlace.name);
		}
	}

	private static void notifyAll(String cityName, String hotelName) {
		// Non ho istanziato alcun socket per l'invio delle notifiche
		if (notificationSocket == null)
			return;

		// Preparo il pacchetto da inviare sul gruppo multicast
		byte[] buf = String.format("Il nuovo miglior hotel di %s è '%s'!", cityName, hotelName).getBytes();
		DatagramPacket dp = new DatagramPacket(buf, buf.length, multicastGroup, multicastPort);

		try {
			// Mando la notifica sul gruppo multicast
			notificationSocket.send(dp);
			System.out.println("[SERVER] Notifica inviata");
		} catch (IOException e) {
			// Errore di invio
		}
	}

}
