import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantLock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;


/*
################################################################################

L'orderBook sara' strutturato come ConcurrentSkipListMap priceSegments, nel quale ci sara' l'insieme delle offerte attive aggiornate all'ultimo ordine
	- come chiave ci sara' il prezzo dell'asset
	- come valore ci sara' l'insieme degli ordini
		- gli ordini saranno rappresentati come oggetti offers ordinati FIFO

L'orderBook contiene una classe Order contenente dei metodi che descrivono le principali funzionalita' offerte dal servizio CROSS.
L'orderBook contiene inoltre la struttura, stopOrders necessaria per tenere traccia dinamicamente degli stopOrders, verificandone le condizioni ad ogni aggiornamento di priceSegments

################################################################################
*/


/**
 * Classe principale per la gestione degli ordini nel sistema CROSS.
 * Fornisce funzionalità per l'inserimento, modifica e cancellazione di ordini di mercato, limite e stop.
 */
public class OrderBook {
    
	// Struttura dati per gestire gli ordini per prezzo in modo thread-safe
	private final static ConcurrentSkipListMap<Integer, LinkedList<Order>> priceSegments = new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    // Lista per gestire gli ordini di tipo "stop"
    private final LinkedList<Order> stopOrders = new LinkedList<>();
    
    // Lock per gestire l'accesso concorrente agli ordini
	private final ReentrantLock lock = new ReentrantLock();
    
	// Percorso e nome del file per la persistenza degli ordini
    private static final String PATH_FILE = mainServer.class.getProtectionDomain().getCodeSource().getLocation().getPath();
    private static final String NAME_FILE_ORDERS = "ordersServer.json";
	static File file = new File(PATH_FILE, NAME_FILE_ORDERS);

    /**
     * Inizializza la cronologia degli ordini creando il file di persistenza se non esiste.
     */
    public static void setUpOrderHistory() {
    	
    	if (!file.exists()) { file = utilities.createFile(PATH_FILE, NAME_FILE_ORDERS); } 
    }
    
    /**
     * Metodo per recuperare la cronologia dei prezzi in un determinato mese.
     * 
     * @param month Mese per cui recuperare i dati (valore numerico tra 1 e 12).
     * @return Mappa che associa i giorni del mese ai dati dei prezzi (apertura, chiusura, massimo, minimo).
     */
    public static Map<Integer, DayPriceData> getPriceHistory(int month) {
        Gson gson = new Gson();
        List<Order> listOrders = null;
        Map<Integer, DayPriceData> priceHistory = new HashMap<>();

        try (FileReader reader = new FileReader(file)) {
            Type orderListType = new TypeToken<List<Order>>() {}.getType();
            listOrders = gson.fromJson(reader, orderListType);

            if (listOrders != null) {
                for (Order order : listOrders) {
                    if (order != null) { // Verifica che l'ordine non sia null
                        Instant instant = Instant.ofEpochSecond(order.getTimestamp());
                        int orderMonth = instant.atZone(ZoneId.of("GMT")).getMonthValue();
                        int orderDay = instant.atZone(ZoneId.of("GMT")).getDayOfMonth();

                        // Considera solo gli ordini del mese richiesto
                        if (orderMonth == month) {
                            // Recupera o inizializza i dati per il giorno specifico
                            DayPriceData dayData = priceHistory.computeIfAbsent(orderDay, k -> new DayPriceData());

                            // Aggiorna i valori di apertura, chiusura, massimo e minimo
                            dayData.update(order.getPrice());
                        }
                    }
                }
            } else {
                System.err.println("La lista degli ordini è nulla.");
            }
        } catch (IOException e) {
            System.err.println("Errore durante la lettura del file: " + e.getMessage());
            e.printStackTrace();
        }

        return priceHistory;
    }



    
    public int getDayOfMonth(Order o) {
        return Instant.ofEpochSecond(o.getTimestamp())
                      .atZone(ZoneId.systemDefault())
                      .getDayOfMonth();
    }

    
    public void updateFileOrders(Order order) throws IOException {
		utilities.ThreadSafeFileWriter(file, order, Order.class);
	}
	

    /**
     * Metodo per inserire un ordine limite.
     * 
     * @param tipo Tipo dell'ordine ("ask" per vendita, "bid" per acquisto).
     * @param size Dimensione dell'ordine.
     * @param price Prezzo limite dell'ordine.
     * @return L'ordine inserito.
     * @throws IOException Se si verifica un errore durante la scrittura nel file.
     */
	public Order insertLimitOrder(String tipo, int size, int price) throws IOException {

				Order new_offer = null;
				List<Integer> interested = null;

				switch (tipo) {
					/*
					Se e' ask allora e' un offerta di vendita:
						- se c'e' la key del prezzo allora controllo
							- se c'e' un offerta di acquisto a quel prezzo prima di vendere controllo:
								- se c'e' un offerta piu' bassa con quantita' uguale o maggiore? se si vendo a quell'offerta, senno' piazzo l'offerta di vendita
					*/
					case "ask":
						if (priceSegments.containsKey(price)) {
			                // Logica per la gestione degli ordini di vendita ("ask")
			                // Controlla se esistono offerte a quel prezzo
			                // Esegue la vendita se trova un'offerta compatibile, altrimenti aggiunge un nuovo ordine di vendita
							LinkedList<Order> offers = priceSegments.get(price);
							// Se io sono un offerta di vendita e trovo un offerta di acquisto con disponibilita adeguata oppure migliore, vendo
							if (offers.getFirst().getType() == "bid" && size >= getSizeOffersByPrice(price) || lookingForBestOffer(size, "ask") != price) {

								int betterPrice;
								if ((betterPrice = lookingForBestOffer(size, "ask")) != price) {
									// Vendo all'offerta di acquisto migliore
									interested = sell(betterPrice, size);
									new_offer = new Order("ask", "limit", size, price);
									new_offer.setUsersToNotifyByOrderId(interested);

								} else {
								// Vendo all'offerta di acquisto limite
									interested = sell(price, size);
									new_offer = new Order("ask", "limit", size, price);
									new_offer.setUsersToNotifyByOrderId(interested);

								}
							// Allora vuoldire che le offerte a quel prezzo sono ask
								// - pusho sull'array segment la mia offerta
							} else {
								new_offer = new Order("ask", "limit", size, price);
								priceSegments.get(price).add(new_offer);
							}


						// Non c'e' la cella che descrive l'asset a quel prezzo, percio' la piazzo
			            } else {
			                // Creo una nuova lista e aggiungo l'offerta
			            	new_offer = new Order("ask", "limit", size, price);
			                LinkedList<Order> newOffersList = new LinkedList<>();
			                newOffersList.add(new_offer);
			                priceSegments.put(price, newOffersList);
			            }
			            break;

					case "bid":
		                // Logica per la gestione degli ordini di acquisto ("bid")
		                // Simile al caso "ask", ma per gli acquisti
					    if (priceSegments.containsKey(price)) {
					        LinkedList<Order> offers = priceSegments.get(price);

					        // Se trovo un'offerta di vendita valida e compatibile
					        if (offers.getFirst().getType().equals("ask") && size <= getSizeOffersByPrice(price) || lookingForBestOffer(size, "bid") != price) {

					            int betterPrice;
					            if ((betterPrice = lookingForBestOffer(size, "bid")) != price) {
					                // Compro all'offerta di vendita migliore
					            	interested = purchase(betterPrice, size);
							    	new_offer = new Order("bid", "limit", size, price);
									new_offer.setUsersToNotifyByOrderId(interested);

					            } else {
					            // Compro all'offerta limite di vendita
					            	interested = purchase(price, size);
							    	new_offer = new Order("bid", "limit", size, price);
									new_offer.setUsersToNotifyByOrderId(interested);

					            }
					        } else {
					            // Non ci sono offerte di vendita compatibili, piazzo il mio ordine di acquisto
					        	new_offer = new Order("bid", "limit", size, price);
					            priceSegments.get(price).add(new_offer);
					        }

					    } else {
					        // Non c'è alcuna cella a quel prezzo, aggiungo il mio ordine di acquisto
					    	new_offer = new Order("bid", "limit", size, price);
					        LinkedList<Order> newOffersList = new LinkedList<>();

					        newOffersList.add(new_offer);
					        priceSegments.put(price, newOffersList);
					    }
					    break;

					default:
			            throw new IllegalArgumentException("Tipo ordine non valido: " + tipo);
				}
			if (new_offer != null) { updateFileOrders(new_offer); }
			return new_offer;

		}

    /**
     * Metodo per inserire un ordine di mercato.
     * 
     * @param tipo Tipo dell'ordine ("ask" o "bid").
     * @param size Dimensione dell'ordine.
     * @return L'ordine di mercato inserito.
     * @throws IOException Se si verifica un errore durante la scrittura nel file.
     */
	public Order insertMarketOrder(String tipo, int size) throws IOException {
	    int price;
	    Order marketOrder = null;
	    List<Integer> interested = null;

	    switch (tipo) {
	        case "ask":
                // Logica per trovare il miglior prezzo di acquisto e vendere
	            price = lookingForBestOffer(size, "ask");
	            if (price > 0) {
	                interested = sell(price, size); // Esegui la vendita al miglior prezzo
	                marketOrder = new Order(tipo, "market", size, price);
	                marketOrder.setUsersToNotifyByOrderId(interested);
	            }
	            break;

	        case "bid":
                // Logica per trovare il miglior prezzo di vendita e acquistare
	            price = lookingForBestOffer(size, "bid");
	            System.out.println("looking for best offer:" + price);
	            if (price > 0) {
	                interested = purchase(price, size); // Esegui l'acquisto al miglior prezzo
	                marketOrder = new Order(tipo, "market", size, price);
	                marketOrder.setUsersToNotifyByOrderId(interested);
	            }
	            break;
	    }
		if (marketOrder != null) { updateFileOrders(marketOrder); }
	    return marketOrder;
	}
	
    /**
     * Metodo per cancellare un ordine specifico.
     * 
     * @param orderId ID dell'ordine da cancellare.
     */
	public void cancelOrder(int orderId) {
	    lock.lock();
	    try {
	        boolean orderFound = false;
            
	        // Logica per cercare e rimuovere l'ordine specificato
	        // Itera su tutte le entry della mappa
	        for (Map.Entry<Integer, LinkedList<Order>> entry : priceSegments.entrySet()) {
	            LinkedList<Order> orders = entry.getValue();

	            // Usa un iterator per evitare ConcurrentModificationException
	            Iterator<Order> iterator = orders.iterator();
	            while (iterator.hasNext()) {
	                Order order = iterator.next();
	                if (order.getOrderId() == orderId) { // Confronto diretto per gli ID
	                    iterator.remove();
	                    orderFound = true;
	                    System.out.println("Order " + orderId + " canceled successfully.");
	                    break; // Esci dal ciclo se l'ordine è stato trovato
	                }
	            }

	            if (orderFound) {
	                break; // Esci dal ciclo esterno se l'ordine è stato trovato
	            }
	        }

	        if (!orderFound) {
	            System.err.println("Order " + orderId + " not found.");
	        }
	    } finally {
	        lock.unlock();
	    }
	}
	
    /**
     * Classe interna per gestire i dati di prezzo giornalieri.
     */
	public Order insertStopOrder(String type, int size, int stopPrice) throws IOException {
	    lock.lock();
	    try {
	        Order stopOrder = new Order(type, "stop", size, stopPrice);
	        stopOrders.add(stopOrder);
			//updateFileOrders(stopOrder);
	        return stopOrder;
	    } finally {
	        lock.unlock();
	    }
	}


	// Metodo per controllare ed eseguire gli Stop Orders
	public synchronized void checkStopOrders() throws IOException {

		if (!stopOrders.isEmpty()) {
	        // Itera sulla lista
	        for (Order order : stopOrders) {

	        	switch (order.getType()) {
	        		case "ask":
	        			// Se le proposte di acquisto attuali sono minori del mio stopPrice, vendo
	        			if (lookingForBestOffer(order.getSize(), "ask") <= order.getPrice()) {insertMarketOrder("ask", order.getSize());}
	        			break;
	        		case "bid":
	        			// Se le proposte di vendita salgono troppo, compro
	        			if (lookingForBestOffer(order.getSize(), "bid") >= order.getPrice()) {insertMarketOrder("bid", order.getSize());}
	        			break;

	       }}}}

	// Metodo che compra al prezzo specificato, di size specificata, in ordine FIFO degli ordini
	public static List<Integer> sell(int price, int size) {

	    LinkedList<Order> offers = priceSegments.get(price);
	    int remainSize = size;
	    List<Integer> usersToNotifyByOrderId = new ArrayList<>();

		/*
		Vendo al prezzo finche' non esaurisco la size:
			- se size e' compresa nella size dell'offerta di acquisto
				- se e' uguale elimino l'offerta di acquisto
				- se e' minore aggiorno la size dell'offerta di acquisto
		*/
		do {
			Order firstOffer = offers.getFirst();

			// Caso in cui l'offerta di acquisto e' minore:
				// - aggiorno il remain size
				// - elimino l'offerta di acquisto
			if (firstOffer.getSize() - remainSize < 0) {
				remainSize -= firstOffer.getSize();
				offers.removeFirst();
			} else if (firstOffer.getSize() - remainSize == 0) {
				offers.removeFirst();
				break;
			} else {
				firstOffer.setSize(firstOffer.getSize() - remainSize);
				break;
			}

			usersToNotifyByOrderId.add(offers.getFirst().getOrderId());

		} while (remainSize != 0);

		return usersToNotifyByOrderId;
	}

	public static List<Integer> purchase(int price, int size) {
	    LinkedList<Order> offers = priceSegments.get(price);
	    System.out.println(offers.toString());
	    int remainSize = size;

	    List<Integer> usersToNotifyByOrderId = new ArrayList<>();

	    /*
	    Acquisto al prezzo specificato finché non esaurisco la quantità richiesta:
	    - Se la size dell'offerta è maggiore o uguale alla quantità richiesta, aggiorno o rimuovo l'offerta.
	    - Altrimenti, riduco la quantità richiesta e continuo con l'offerta successiva.
	    */
	    do {
	    	if (offers == null || offers.isEmpty()) {
	    		System.out.println("Nessuna offerta trovata per il prezzo: " + price);
	    		break;
	    	} else {
	    		
	    		Order firstOffer = offers.getFirst();
	    		
	    		if (firstOffer.getSize() <= remainSize) {
	    			remainSize -= firstOffer.getSize();
	    			offers.removeFirst();
	    		} else {
	    			firstOffer.setSize(firstOffer.getSize() - remainSize);
	    			remainSize = 0;
	    		}
	    		
	    		// Rimuovo il segmento
	    		if (offers.isEmpty()) {
	    			priceSegments.remove(price);
	    		}
	    		
	    		//usersToNotifyByOrderId.add(offers.getFirst().getOrderId());
	    		usersToNotifyByOrderId.add(firstOffer.getOrderId());

	    	}

	    } while (remainSize > 0 && !offers.isEmpty());

	    // Notifica se l'ordine non è stato completamente soddisfatto
	    if (remainSize > 0) {
	        System.out.println("Ordine parzialmente soddisfatto. Quantità residua: " + remainSize);
	    }

	    return usersToNotifyByOrderId;
	}


	// Restituisce la size totale di tutte le offerte, non specifica se ask o bid
	public static int getSizeOffersByPrice(int price) {
	    LinkedList<Order> offers = priceSegments.get(price);
	    int totalSize = 0;
	    for (Order offer : offers) {
	        totalSize += offer.getSize();
	    }
	    return totalSize;
	}

	public static int lookingForBestOffer(int size, String type) {
	    int currentPrice = 0;

	    // Se io sono un'offerta di vendita voglio venderlo al prezzo più alto
	    if (type.equals("ask")) {
	        // Ottieni l'insieme delle chiavi in ordine decrescente, perché devo trovare le offerte di acquisto più alte in prezzo
	        for (Map.Entry<Integer, LinkedList<Order>> entry : priceSegments.entrySet()) {
	            currentPrice = entry.getKey();
	            LinkedList<Order> orders = entry.getValue();

	            // Salta se la lista è vuota
	            if (orders.isEmpty()) {
	                continue;
	            }

	            // Se il tipo dell'offerta non è bid non mi interessa
	            if (!orders.getFirst().getType().equals("bid")) {
	                continue;
	            }

	            // Controlla se il prezzo corrente è minore di "price" e ha disponibilità sufficiente
	            if (getSizeOffersByPrice(currentPrice) >= size) {
	                break; // Uscita dal ciclo appena trovato un miglior prezzo
	            }
	        }
	    }

	    // Se io sono un'offerta di acquisto, voglio comprare al prezzo più basso
	    if (type.equals("bid")) {
	        for (Map.Entry<Integer, LinkedList<Order>> entry : priceSegments.descendingMap().entrySet()) {
	            currentPrice = entry.getKey();
	            LinkedList<Order> orders = entry.getValue();

	            // Salta se la lista è vuota
	            if (orders.isEmpty()) {
	                continue;
	            }

	            // Se il tipo dell'offerta non è ask non mi interessa
	            if (!orders.getFirst().getType().equals("ask")) {
	                continue;
	            }

	            // Se c'è un prezzo di vendita minore del mio limite di vendita, ritorno il prezzo
	            if (getSizeOffersByPrice(currentPrice) >= size) {
	                break;
	            }
	        }
	    }

	    return currentPrice;
	}
	
    public static File createFileOrder(String path, String name) {
        
    	File file = new File("/home/re/progetto/src", "output.json");

    	try {
    	            // Controlla se la directory esiste
    	            File directory = file.getParentFile();
    	            if (!directory.exists()) {
    	                // Crea la directory se non esiste
    	                if (directory.mkdirs()) {
    	                    System.out.println("Directory creata: " + directory.getAbsolutePath());
    	                } else {
    	                    System.out.println("Errore nella creazione della directory!");
    	                    return null;
    	                }
    	            }
    	            if (file.createNewFile()) {
    	                  System.out.println("File creato: " + file.getAbsolutePath());
    	            } else {
    	                  System.out.println("Errore nella creazione del file!");
    	                  return null;
    	            }

    	        } catch (IOException e) {
    	            e.printStackTrace();
    	        }
    	return file;
    }
    
    /**
     * Classe per rappresentare la risposta al getOrdersHistory.
     */
    public static class DayPriceData {
        private int openingPrice;
        private int closingPrice;
        private int maxPrice;
        private int minPrice;

        public DayPriceData() {
            this.maxPrice = Integer.MIN_VALUE;
            this.minPrice = Integer.MAX_VALUE;
        }

        public void update(int price) {
            if (maxPrice == Integer.MIN_VALUE) { // Imposta il prezzo di apertura al primo prezzo ricevuto
                openingPrice = price;
            }
            closingPrice = price; // Aggiorna il prezzo di chiusura con l'ultimo prezzo ricevuto
            maxPrice = Math.max(maxPrice, price);
            minPrice = Math.min(minPrice, price);
        }

        @Override
        public String toString() {
            return "{"
                    + "\"openingPrice\": " + openingPrice
                    + ", \"closingPrice\": " + closingPrice
                    + ", \"maxPrice\": " + maxPrice
                    + ", \"minPrice\": " + minPrice
                    + "}";
        }
    }
    /**
     * Classe per rappresentare un ordine.
     */
    public static class Order {
        private static int globalOrderId = 0; // Contatore globale
        private final int orderId; // ID univoco per ogni ordine
        private final String type;
        private final String orderType;
        private Integer size;
        private final Integer price;
        private final Integer timestamp;
        private List<Integer> usersToNotifyByOrderId = null;

        public Order(String type, String orderType, Integer size, Integer price) {
            this.orderId = ++globalOrderId; // Incrementa il contatore globale e assegna un ID
            this.type = type;
            this.orderType = orderType;
            this.size = size;
            this.price = price;
            this.timestamp = (int) Instant.now().getEpochSecond();
        }

        // Getters
        public int getOrderId() { return this.orderId; }
        public String getType() { return this.type; }
        public Integer getTimestamp() { return this.timestamp; }
        public Integer getSize() { return this.size; }
        public Integer getPrice() { return this.price; }
        public List<Integer> getUsersToNotifyByOrderId() { return this.usersToNotifyByOrderId; }

        // Setters
        public void setSize(int newSize) { this.size = newSize; }
        public void setUsersToNotifyByOrderId(List<Integer> lista) { this.usersToNotifyByOrderId = lista; }

        @Override
        public String toString() {
            return "Order{id=" + orderId + ", size=" + size + ", price=" + price + "}";
        }

        public String toJson() {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();

            // Creazione del JSON per l'ordine
            JsonObject orderJson = new JsonObject();
            orderJson.addProperty("orderId", this.orderId);
            orderJson.addProperty("type", this.type);
            orderJson.addProperty("orderType", this.orderType);
            orderJson.addProperty("size", this.size);
            orderJson.addProperty("price", this.price);
            orderJson.addProperty("timestamp", this.timestamp);

            JsonArray tradesArray = new JsonArray();
            tradesArray.add(orderJson);

            JsonObject finalJson = new JsonObject();
            finalJson.add("trades", tradesArray);

            return gson.toJson(finalJson);
        }
    }



}
