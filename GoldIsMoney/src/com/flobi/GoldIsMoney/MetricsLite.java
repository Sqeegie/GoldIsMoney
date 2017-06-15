package com.flobi.GoldIsMoney;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.logging.Level;

public class MetricsLite {

	/**
	 * The current revision number
	 */
	private final static int REVISION = 5;

	/**
	 * The base url of the metrics domain
	 */
	private static final String BASE_URL = "http://mcstats.org";

	/**
	 * The url used to report a server's status
	 */
	private static final String REPORT_URL = "/report/%s";

	/**
	 * The file where guid and opt out is stored in
	 */
	private static final String CONFIG_FILE = "plugins/PluginMetrics/config.yml";

	/**
	 * Interval of time to ping (in minutes)
	 */
	private final static int PING_INTERVAL = 10;

	/**
	 * The plugin this metrics submits for
	 */
	private final Plugin plugin;

	/**
	 * The plugin configuration file
	 */
	private final YamlConfiguration configuration;

	/**
	 * The plugin configuration file
	 */
	private final File configurationFile;

	/**
	 * Unique server id
	 */
	private final String guid;

	/**
	 * Lock for synchronization
	 */
	private final Object optOutLock = new Object();

	/**
	 * Id of the scheduled task
	 */
	private volatile int taskId = -1;

	public MetricsLite(Plugin plugin) throws IOException {
		if (plugin == null) {
			throw new IllegalArgumentException("Plugin cannot be null");
		}

		this.plugin = plugin;

		// load the config
		configurationFile = new File(CONFIG_FILE);
		configuration = YamlConfiguration.loadConfiguration(configurationFile);

		// add some defaults
		configuration.addDefault("opt-out", false);
		configuration.addDefault("guid", UUID.randomUUID().toString());

		// Do we need to create the file?
		if (configuration.get("guid", null) == null) {
			configuration.options().header("http://mcstats.org").copyDefaults(true);
			configuration.save(configurationFile);
		}

		// Load the guid then
		guid = configuration.getString("guid");
	}

	/**
	 * Start measuring statistics. This will immediately create an async
	 * repeating task as the plugin and send the initial data to the metrics
	 * backend, and then after that it will post in increments of PING_INTERVAL
	 * * 1200 ticks.
	 *
	 * @return True if statistics measuring is running, otherwise false.
	 */
	@SuppressWarnings("deprecation")
	public boolean start() {
		synchronized (optOutLock) {
			// Did we opt out?
			if (isOptOut()) {
				return false;
			}

			// Is metrics already running?
			if (taskId >= 0) {
				return true;
			}

			// Begin hitting the server with glorious data
			taskId = plugin.getServer().getScheduler().scheduleAsyncRepeatingTask(plugin, new Runnable() {

				private boolean firstPost = true;

				public void run() {
					try {
						// This has to be synchronized or it can collide with the disable method.
						synchronized (optOutLock) {
							// Disable Task, if it is running and the server owner decided to opt-out
							if (isOptOut() && taskId > 0) {
								plugin.getServer().getScheduler().cancelTask(taskId);
								taskId = -1;
							}
						}

						// We use the inverse of firstPost because if it is the first time we are posting,
						// it is not a interval ping, so it evaluates to FALSE
						// Each time thereafter it will evaluate to TRUE, i.e PING!
						postPlugin(!firstPost);

						// After the first post we set firstPost to false
						// Each post thereafter will be a ping
						firstPost = false;
					}
					catch (IOException e) {
						Bukkit.getLogger().log(Level.INFO, "[Metrics] {0}", e.getMessage());
					}
				}
			}, 0, PING_INTERVAL * 1200);

			return true;
		}
	}

	/**
	 * Has the server owner denied plugin metrics?
	 *
	 * @return
	 */
	public boolean isOptOut() {
		synchronized (optOutLock) {
			try {
				// Reload the metrics file
				configuration.load(CONFIG_FILE);
			}
			catch (IOException ex) {
				Bukkit.getLogger().log(Level.INFO, "[Metrics] {0}", ex.getMessage());
				return true;
			}
			catch (InvalidConfigurationException ex) {
				Bukkit.getLogger().log(Level.INFO, "[Metrics] {0}", ex.getMessage());
				return true;
			}
			return configuration.getBoolean("opt-out", false);
		}
	}

	/**
	 * Enables metrics for the server by setting "opt-out" to false in the
	 * config file and starting the metrics task.
	 *
	 * @throws IOException
	 */
	public void enable() throws IOException {
		// This has to be synchronized or it can collide with the check in the task.
		synchronized (optOutLock) {
			// Check if the server owner has already set opt-out, if not, set it.
			if (isOptOut()) {
				configuration.set("opt-out", false);
				configuration.save(configurationFile);
			}

			// Enable Task, if it is not running
			if (taskId < 0) {
				start();
			}
		}
	}

	/**
	 * Disables metrics for the server by setting "opt-out" to true in the
	 * config file and canceling the metrics task.
	 *
	 * @throws IOException
	 */
	public void disable() throws IOException {
		// This has to be synchronized or it can collide with the check in the task.
		synchronized (optOutLock) {
			// Check if the server owner has already set opt-out, if not, set it.
			if (!isOptOut()) {
				configuration.set("opt-out", true);
				configuration.save(configurationFile);
			}

			// Disable Task, if it is running
			if (taskId > 0) {
				this.plugin.getServer().getScheduler().cancelTask(taskId);
				taskId = -1;
			}
		}
	}

	/**
	 * Generic method that posts a plugin to the metrics website
	 */
	private void postPlugin(boolean isPing) throws IOException {
		// The plugin's description file containg all of the plugin data such as name, version, author, etc
		final PluginDescriptionFile description = plugin.getDescription();

		// Construct the post data
		final StringBuilder data = new StringBuilder();
		data.append(encode("guid")).append('=').append(encode(guid));
		encodeDataPair(data, "version", description.getVersion());
		encodeDataPair(data, "server", Bukkit.getVersion());
		encodeDataPair(data, "players", Integer.toString(Bukkit.getServer().getOnlinePlayers().size()));
		encodeDataPair(data, "revision", String.valueOf(REVISION));

		// If we're pinging, append it
		if (isPing) {
			encodeDataPair(data, "ping", "true");
		}

		// Create the url
		URL url = new URL(BASE_URL + String.format(REPORT_URL, encode(plugin.getDescription().getName())));

		// Connect to the website
		URLConnection connection;

		// Mineshafter creates a socks proxy, so we can safely bypass it
		// It does not reroute POST requests so we need to go around it
		if (isMineshafterPresent()) {
			connection = url.openConnection(Proxy.NO_PROXY);
		}
		else {
			connection = url.openConnection();
		}

		connection.setDoOutput(true);

		// Write the data
		final OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());
		writer.write(data.toString());
		writer.flush();

		// Now read the response
		final BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
		final String response = reader.readLine();

		// close resources
		writer.close();
		reader.close();

		if (response == null || response.startsWith("ERR")) {
			throw new IOException(response); //Throw the exception
		}
		//if (response.startsWith("OK")) - We should get "OK" followed by an optional description if everything goes right
	}

	/**
	 * Check if mineshafter is present. If it is, we need to bypass it to send
	 * POST requests
	 *
	 * @return
	 */
	private boolean isMineshafterPresent() {
		try {
			Class.forName("mineshafter.MineServer");
			return true;
		}
		catch (Exception e) {
			return false;
		}
	}

	/**
	 * <p>
	 * Encode a key/value data pair to be used in a HTTP post request. This
	 * INCLUDES a & so the first key/value pair MUST be included manually, e.g:
	 * </p>
	 * <code>
	 * StringBuffer data = new StringBuffer();
	 * data.append(encode("guid")).append('=').append(encode(guid));
	 * encodeDataPair(data, "version", description.getVersion());
	 * </code>
	 *
	 * @param buffer
	 * @param key
	 * @param value
	 * @return
	 */
	private static void encodeDataPair(final StringBuilder buffer, final String key, final String value) throws UnsupportedEncodingException {
		buffer.append('&').append(encode(key)).append('=').append(encode(value));
	}

	/**
	 * Encode text as UTF-8
	 *
	 * @param text
	 * @return
	 */
	private static String encode(String text) throws UnsupportedEncodingException {
		return URLEncoder.encode(text, "UTF-8");
	}

}
