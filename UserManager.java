import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Classe per la gestione degli utenti nel sistema CROSS.
 * Consente di registrare, autenticare, aggiornare le credenziali e gestire notifiche per gli utenti.
 */
public class UserManager {
	
    private static final String PATH_FILE = mainServer.class.getProtectionDomain().getCodeSource().getLocation().getPath();
    private static final String NAME_FILE_USERS = "usersServer.json";
	static File file = new File(PATH_FILE, NAME_FILE_USERS);


    /**
     * Classe interna che rappresenta un utente.
     */
    public static class User {
    	
        private String username;
        private String password;
        private String ipAddress;
        private boolean login = false;
        private List<OrderBook.Order> notifyList;

        public User(String user, String pass) {
            this.username = user;
            this.password = pass;
        	this.notifyList = new ArrayList<>();

        }
        
        // Sceglie quello desiderato dalla firma
        public User(String user, String pass, String ip, List<OrderBook.Order> list) {
        	this.username = user;
        	this.password = pass;
        	this.ipAddress = ip;
        	this.notifyList = list;
        }
        

        public String getUsername() { return username; }
        public String getPassword() { return password; }
        public String getIpAddress() { return ipAddress; }
        public boolean isLogin() { return login; }
        public void setPassword(String new_password) { this.password = new_password;}
        public void setIp(String string) { this.ipAddress = string; }
        public void login() { this.login = true; }
        public void logout() { this.login = false; }
        public void addNotify(OrderBook.Order o) { this.notifyList.add(o); }
        public void clearNotifyList() { this.notifyList.clear(); }
        public List<OrderBook.Order> getNotifyList() { return this.notifyList; }
    }
    
    /**
     * Inizializza la cronologia degli utenti caricandoli dal file di persistenza.
     * 
     * @param uDB Struttura dati per memorizzare gli utenti.
     * @return Il file di persistenza degli utenti.
     */
    public static File setUpUserHistory(ConcurrentHashMap<UserManager.User, List<OrderBook.Order>> uDB) {
    	// Se il file non esiste allora creo il file, se invece esiste cambia tutto,
    	// faccio un while (finche ho roba da leggere) registro gli utenti 
    	if (!file.exists()) {
    		file = utilities.createFile(PATH_FILE, NAME_FILE_USERS);
       	} else {
       		Gson gson = new Gson();
       		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                Type userList = new TypeToken<List<User>>() {}.getType();
                List<User> users = gson.fromJson(reader, userList);
                
       			for (User user : users) {
       				register(
       						user.getUsername(),
       						user.getPassword(),
       						user.getIpAddress(),
       						uDB,
       						user.getNotifyList(),
       						false);
       			}
       		} catch (IOException e) {
       			e.printStackTrace();
       		}       		
       	}
    	
    	return file;
    }
    
    /**
     * Cerca un utente nella struttura dati basandosi sul nome utente.
     * 
     * @param username Nome utente da cercare.
     * @param uDB Struttura dati degli utenti.
     * @return L'oggetto User corrispondente, o null se non trovato.
     */
    public static User findUserByUsername(String username, ConcurrentHashMap<User, List<OrderBook.Order>> uDB) {
        // Cerca l'utente nella mappa
        for (User user : uDB.keySet()) {
            if (user.getUsername().equals(username)) {
                return user;
            }
        }
        // Restituisce null se l'utente non viene trovato
        return null;
    }
    
    /**
     * Esegue il login di un utente.
     * 
     * @param username Nome utente.
     * @param password Password dell'utente.
     * @param uDB Struttura dati degli utenti.
     * @param clientSocket Socket del client.
     * @param out Output per inviare messaggi al client.
     * @return Oggetto mainServer.Response con il risultato del login.
     */
    public static mainServer.Response login(String username, String password, ConcurrentHashMap<User, List<OrderBook.Order>> uDB, Socket clientSocket, PrintWriter out) {
    	mainServer.Response risp = new mainServer.Response();
      	try {
    		User user = findUserByUsername(username, uDB);
    		
            if (user == null || !user.getPassword().equals(password)) {
                risp.setResponseErrormsg(101, "username/password mismatch or non existent username");
            } else if (user.isLogin()) {
                risp.setResponseErrormsg(102, "user already logged in");
            } else {
	            user.setIp(clientSocket.getInetAddress().getHostAddress());
	            user.login();
	            if (!user.notifyList.isEmpty()) {
                    Gson gson = new GsonBuilder().create();
                    out.println(gson.toJson(user.getNotifyList()));
                    out.flush();
                    user.clearNotifyList();
	            }
	            risp.setResponseErrormsg(100, "OK");
            }
    	} catch (Exception e) {
    		risp.setResponseErrormsg(103, "other error cases");
    	}
    	
    	return risp;
    }

    /**
     * Registra un nuovo utente nel sistema.
     * 
     * @param username Nome utente.
     * @param password Password dell'utente.
     * @param ip Indirizzo IP del client.
     * @param uDB Struttura dati degli utenti.
     * @param list Lista di notifiche (opzionale).
     * @param scrivi Indica se salvare i dati su file.
     * @return Oggetto mainServer.Response con il risultato della registrazione.
     */
    public static mainServer.Response register(String username, String password, String ip, ConcurrentHashMap<User, List<OrderBook.Order>> uDB, List<OrderBook.Order> list ,boolean scrivi) {
        mainServer.Response risp = new mainServer.Response();
    	try {
            if (password.isEmpty()) {
                risp.setResponseErrormsg(101, "invalid password");
            }

            if (findUserByUsername(username, uDB) == null) {
            	User newUser;
              	if (scrivi) { 
              			// Aggiunge l'utente nel database dal file, il quale potrebbe non avere una lista
            			newUser = new User(username, password, ip, list != null ? list : new ArrayList<>());
            	} else {
   	            		newUser = new User(username, password);
            	}
				uDB.put(newUser, new ArrayList<>());
            	risp.setResponseErrormsg(100, "OK");
            	newUser.setIp(ip);

            	if (scrivi) { utilities.ThreadSafeFileWriter(file, newUser, User.class); }


            } else {
            	risp.setResponseErrormsg(102, "username not available");
            }

        } catch (Exception e) {
        	e.printStackTrace();
            risp.setResponseErrormsg(103, "other error cases");
        }
    	
    	return risp;
    }
    
    public static void writeNotify(String username, OrderBook.Order order) {
    	// REQUIRES: il file esiste in formato JSON con l'oggetto utente corrispondente all'username, l'username e' offline
    	// EFFECTS: il file Json viene aggiornato con un add sulla lista corrispondente alle notifiche
   		Gson gson = new Gson();
   		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            Type userList = new TypeToken<List<User>>() {}.getType();
            List<User> users = gson.fromJson(reader, userList);
            
   			for (User user : users) {
   				if (user.getUsername().equals(username)) {
   					user.getNotifyList().add(order);
   					break;
   				}
   			}
   			
   	        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
   	            gson.toJson(users, userList, writer);
   	        }	
   		} catch (IOException e) {
   			e.printStackTrace();
   		}  
    	
    }

    public static mainServer.Response updateCredentials(String username, String currentPassword, String newPassword, ConcurrentHashMap<User, List<OrderBook.Order>> uDB) {
        mainServer.Response risp = new mainServer.Response();
    	User user = findUserByUsername(username, uDB);
        if (user == null || !user.getPassword().equals(currentPassword)) {
            risp.setResponseErrormsg(102, "username/old_password mismatch or non existent username");
        } else 
        if (currentPassword.equals(newPassword)) {
            risp.setResponseErrormsg(103, "new password equal to old one");
            ;
        } else
        if (newPassword.isEmpty()) {
            risp.setResponseErrormsg(101, "invalid new password");
        } else {
        user.setPassword(newPassword);
        risp.setResponseErrormsg(100, "OK");
        }
        
        return risp;
        
    }

    public static mainServer.Response logout(String username, ConcurrentHashMap<User, List<OrderBook.Order>> uDB) {
    	
    	mainServer.Response risp = new mainServer.Response();
       	User user = findUserByUsername(username, uDB);
        if (user == null || !user.isLogin()) {
            risp.setResponseErrormsg(101, "username/connection mismatch or user not logged in");
        } else {
           	user.logout();
        	risp.setResponseErrormsg(100, "OK");
        }
        return risp;
    }
}
