import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Classe di utilità che fornisce metodi statici per gestire file, configurazioni e scrittura sicura in ambiente multi-thread.
 */
public class utilities {
	
    /**
     * Classe interna per il caricamento di configurazioni da file.
     */
	public class ConfigLoader {
        
		/**
         * Carica un file di configurazione specificato dal percorso fornito.
         * 
         * @param filePath Percorso del file di configurazione.
         * @return Oggetto Properties contenente le chiavi e i valori del file.
         * @throws IOException Se si verifica un errore di lettura del file.
         */
	    public static Properties loadConfig(String filePath) throws IOException {
	        Properties properties = new Properties();
	        try (FileInputStream input = new FileInputStream(filePath)) {
	            properties.load(input);
	        }
	        return properties;
	    }
	}
	
    /**
     * Crea un nuovo file nella directory specificata. Se la directory non esiste, la crea automaticamente.
     * 
     * @param path Percorso della directory.
     * @param name Nome del file da creare.
     * @return Oggetto File rappresentante il file creato, o null in caso di errore.
     */
	public static File createFile(String path, String name) {
        
    	File file = new File(path, name);

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
    	            // Crea il file
    	            if (file.createNewFile()) {
    	                  System.out.println("File creato: " + file.getAbsolutePath());
    	                  
    	                  // Scrive un array vuoto iniziale
    	                  try (FileWriter writer = new FileWriter(file)) {
    	                      writer.write("[]");
    	                  }
    	                  
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
     * Metodo thread-safe per scrivere un oggetto in un file. Il metodo legge il contenuto esistente,
     * aggiorna con il nuovo oggetto e riscrive il file.
     * 
     * @param file File su cui scrivere.
     * @param obj Oggetto da aggiungere al file.
     * @param clas Classe del tipo dell'oggetto.
     * @param <T> Tipo dell'oggetto da scrivere.
     * @throws IOException Se si verifica un errore durante la lettura o scrittura del file.
     */
	public synchronized static <T> void ThreadSafeFileWriter(File file, T obj, Class<T> clas) throws IOException {
	    try {
	        Gson gson = new GsonBuilder().setPrettyPrinting().create();
	        Type listType = TypeToken.getParameterized(List.class, clas).getType();
	        List<T> list;

	        // Legge i dati esistenti nel file
	        try (FileReader reader = new FileReader(file)) {
	            list = gson.fromJson(reader, listType);
	        }

	        // Se il file è vuoto o non contiene dati validi, inizializza una nuova lista
	        if (list == null) {
	            list = new ArrayList<>();
	        }

	        // Aggiunge l'oggetto alla lista
	        list.add(obj);

	        // Scrive la lista aggiornata nel file
	        try (FileWriter writer = new FileWriter(file)) {
	            gson.toJson(list, writer);
	        }
	    } catch (IOException e) {
	        e.printStackTrace();
	        throw e; 
	    }
	}



}
