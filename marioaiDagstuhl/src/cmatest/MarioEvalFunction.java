package cmatest;

import java.io.IOException;
import java.util.*;

import ch.idsia.mario.engine.level.Level;
import ch.idsia.mario.engine.level.LevelParser;
import ch.idsia.tools.EvaluationInfo;
import communication.GANProcess;
import communication.MarioProcess;
import fr.inria.optimization.cmaes.fitness.IObjectiveFunction;
import javafx.geometry.Pos;
import reader.JsonReader;
import static reader.JsonReader.JsonToDoubleArray;
import static reader.JsonReader.JsonToInt;

public class MarioEvalFunction implements IObjectiveFunction {

	// This is the distance that Mario traverses when he beats the short levels
	// that we are generating. It would need to be changed if we train on larger
	// levels or in any way change the level length.
	public static final int LEVEL_LENGTH = 704;

	private GANProcess ganProcess;
	private MarioProcess marioProcess;

	// changing floor will change the reason for termination
	// (in conjunction with the target value)
	// see cma.options.stopFitness
	static double floor = 0.0;

	Map<Integer, String> tileEncoding = new HashMap<Integer, String>() {{
		put(0, "Ground");
		put(1, "Breakable");
		put(2, "Empty");
		put(3, "Full Question Block");
		put(4, "Empty Question Block");
		put(5, "Enemy");
		put(6, "Top-left pipe");
		put(7, "Top-right pipe");
		put(8, "Left pipe");
		put(9, "Right pipe");
		put(10, "Coin");
	}};

	public MarioEvalFunction() throws IOException {
		// set up process for GAN
		activeFitMetric = fitMetric.EnemySparsity;
		ganProcess = new GANProcess();
		ganProcess.start();
		// set up mario game
		marioProcess = new MarioProcess();
		marioProcess.start();
		// consume all start-up messages that are not data responses
		String response = "";
		while(!response.equals("READY")) {
			response = ganProcess.commRecv();
		}
	}

	public MarioEvalFunction(fitMetric fit_metric) throws IOException {
		activeFitMetric = fit_metric;
		// set up process for GAN
		ganProcess = new GANProcess();
		ganProcess.start();
		// set up mario game
		marioProcess = new MarioProcess();
		marioProcess.start();        
		// consume all start-up messages that are not data responses
		String response = "";
		while(!response.equals("READY")) {
			response = ganProcess.commRecv();
		}
	}

	/**
	 * Takes a json string representing a level that conforms to the encoding found above and calculates the standard deviation of the positions of a type of tiles within said level.
	 */

	public Map<String, Integer> getTileCounts (String[] tileTypes, String levelJson) {
		List<List<Integer>> level = JsonToInt(levelJson).get(0); //Only one level is expected here, hence the indexing operation.

		Collections.reverse(level);

		int width = level.get(0).size();
		int height = level.size();


		Map<String, Integer> tileCounts = new HashMap<String, Integer>();

		for (int y = 1; y <= height; y++) { // the origin (x=1, y=1) of the coordinate system is at the bottom left of the level for the calculation of the metrics.
			for (int x = 1; x <= width; x++) {
				int tile = level.get(y-1).get(x-1);
				String tileName = tileEncoding.get(tile);
				if (Arrays.asList(tileTypes).contains(tileName)) {
					tileCounts.merge(tileName, 1, Integer::sum);
				}
			}
		}

		return tileCounts;
	}

	public Map<String, Map<Character, Double>> tilePosMeanFromJson (String[] tileTypes, String levelJson) {
		List<List<Integer>> level = JsonToInt(levelJson).get(0); //Only one level is expected here, hence the indexing operation.

		Collections.reverse(level);

		int width = level.get(0).size();
		int height = level.size();

		Map<String, Map<Character, Double>> PosMeans = new HashMap<String, Map<Character, Double>>();

		for (String tileName : tileTypes) {
			PosMeans.put(tileName, new HashMap<Character, Double>() {{
				put('X', 0d);
				put('Y', 0d);
			}});
		}

		Map<String, Integer> tileCounts = new HashMap<String, Integer>();
		Map<String, Integer> xSums = new HashMap<String, Integer>();
		Map<String, Integer> ySums = new HashMap<String, Integer>();

		for (int y = 1; y <= height; y++) { // the origin (x=1, y=1) of the coordinate system is at the bottom left of the level for the calculation of the metrics.
			for (int x = 1; x <= width; x++) {
				int tile = level.get(y-1).get(x-1);
				String tileName = tileEncoding.get(tile);
				if (Arrays.asList(tileTypes).contains(tileName)) {
					tileCounts.merge(tileName, 1, Integer::sum);
					xSums.merge(tileName, x, Integer::sum);
					ySums.merge(tileName, y, Integer::sum);
				}
			}
		}

		for (String tileName : tileCounts.keySet()) {
			Map<Character,Double> tilePosMap = PosMeans.get(tileName);
			if (tilePosMap != null) {
				tilePosMap.put('X', ((double) xSums.getOrDefault(tileName,0))/tileCounts.getOrDefault(tileName,0));
				tilePosMap.put('Y', ((double) ySums.getOrDefault(tileName,0))/tileCounts.getOrDefault(tileName,0));
			}
		}
		return PosMeans;
	}

	public Map<String, Map<Character, Double>> tilePosStdFromJson (String[] tileTypes, String levelJson, Map<String, Map<Character, Double>> TilePosMeans, Boolean useSparsity) {
		List<List<Integer>> level = JsonToInt(levelJson).get(0); //Only one level is expected here, hence the indexing operation.

		Collections.reverse(level);

		int width = level.get(0).size();
		int height = level.size();

		Map<String, Map<Character, Double>> PosStds = new HashMap<String, Map<Character, Double>>();

		for (String tileName : tileTypes) {
			PosStds.put(tileName, new HashMap<Character, Double>() {{
				put('X', 0d);
				put('Y', 0d);
			}});
		}

		Map<String, Integer> tileCounts = new HashMap<String, Integer>();
		Map<String, Double> xSums = new HashMap<String, Double>();
		Map<String, Double> ySums = new HashMap<String, Double>();

		for (int y = 1; y <= height; y++) { // the origin (x=1, y=1) of the coordinate system is at the bottom left of the level for the calculation of the metrics.
			for (int x = 1; x <= width; x++) {
				int tile = level.get(y-1).get(x-1);
				String tileName = tileEncoding.get(tile);
				if (Arrays.asList(tileTypes).contains(tileName)) {
					tileCounts.merge(tileName, 1, Integer::sum);
					if (useSparsity) {
						xSums.merge(tileName, Math.abs((double) x - TilePosMeans.get(tileName).get('X')), Double::sum);
						ySums.merge(tileName, Math.abs((double) y - TilePosMeans.get(tileName).get('Y')), Double::sum);
					} else {
						xSums.merge(tileName, Math.pow((double) x - TilePosMeans.get(tileName).get('X'),2), Double::sum);
						ySums.merge(tileName, Math.pow((double) y - TilePosMeans.get(tileName).get('Y'),2), Double::sum);
					}

				}
			}
		}

		for (String tileName : tileCounts.keySet()) {
			Map<Character,Double> tilePosMap = PosStds.get(tileName);
			if (tilePosMap != null) {
				tilePosMap.put('X', Math.sqrt(xSums.getOrDefault(tileName,0d)/tileCounts.getOrDefault(tileName,0)));
				tilePosMap.put('Y', Math.sqrt(ySums.getOrDefault(tileName,0d)/tileCounts.getOrDefault(tileName,0)));
			}
		}
		return PosStds;
	}

	public MarioEvalFunction(String GANPath, String GANDim) throws IOException {
		// set up process for GAN
		ganProcess = new GANProcess(GANPath, GANDim);
		ganProcess.start();
		// set up mario game
		marioProcess = new MarioProcess();
		marioProcess.start();        
		// consume all start-up messages that are not data responses
		String response = "";
		while(!response.equals("READY")) {
			response = ganProcess.commRecv();
		}
	}

	/**
	 * Takes a json String representing several levels 
	 * and returns an array of all of those Mario levels.
	 * In order to convert a single level, it needs to be put into
	 * a json array by adding extra square brackets [ ] around it.
	 * @param json Json String representation of multiple Mario levels
	 * @return Array of those levels
	 */
	public static Level[] marioLevelsFromJson(String json) {
		List<List<List<Integer>>> allLevels = JsonReader.JsonToInt(json);
		Level[] result = new Level[allLevels.size()];
		int index = 0;
		for(List<List<Integer>> listRepresentation : allLevels) {
			result[index++] = LevelParser.createLevelJson(listRepresentation);
		}
		return result;
	}
        
        public void exit() throws IOException{
            ganProcess.commSend("0");
        }



	/**
	 * Helper method to get the Mario Level from the latent vector
	 * @param x Latent vector
	 * @return Mario Level
	 * @throws IOException Problems communicating with Python GAN process
	 */
	public Level levelFromLatentVector(double[] x) throws IOException {
		x = mapArrayToOne(x);
		// Interpret x to a level
		// Brackets required since generator.py expects of list of multiple levels, though only one is being sent here
		ganProcess.commSend("[" + Arrays.toString(x) + "]");
		String levelString = ganProcess.commRecv(); // Response to command just sent
		Level[] levels = marioLevelsFromJson("[" +levelString + "]"); // Really only one level in this array
		Level level = levels[0];
		return level;
	}

	public String jsonFromLatentVector (double[] x) throws  IOException {
		x = mapArrayToOne(x);
		ganProcess.commSend("[" + Arrays.toString(x) + "]");
		String levelString = ganProcess.commRecv();
		return (levelString);
	}

	/**
	 * Directly send a string to the GAN (Should be array of arrays of doubles in Json format).
	 * 
	 * Note: A bit redundant: This could be called from the method above.
	 * 
	 * @param
	 * @return
	 * @throws IOException
	 */

	public enum fitMetric {
		EnemySparsity,
		EnemySTDx,
		PowerUpMeanx,
		NumberOfEnemies
	}

	fitMetric activeFitMetric;

	public void setFitMetric (fitMetric desiredFitMetric){
		activeFitMetric = desiredFitMetric;
	}

	public double evaluateFitnessMetric(String levelJson) {
		switch (activeFitMetric) {
			case EnemySTDx: return tilePosStdFromJson(new String[] {"Enemy"},levelJson, tilePosMeanFromJson(new String[] {"Enemy"}, levelJson), false).get("Enemy").get('X');
			case PowerUpMeanx: return tilePosMeanFromJson(new String[] {"Full Question Block"}, levelJson).get("Full Question Block").get('X');
			case EnemySparsity: return tilePosStdFromJson(new String[] {"Enemy"},levelJson, tilePosMeanFromJson(new String[] {"Enemy"}, levelJson), true).get("Enemy").get('X');
			case NumberOfEnemies: return getTileCounts(new String[] {"Enemy"}, levelJson).getOrDefault("Enemy",0);
			default: return 0;
		}
	}

	public String stringToFromGAN(String input) throws IOException {
                double[] x = JsonToDoubleArray(input);
                x = mapArrayToOne(x);
		ganProcess.commSend(Arrays.toString(x));
		String levelString = ganProcess.commRecv(); // Response to command just sent
		return levelString;
	}
	
	/**
	 * Gets objective score for single latent vector.
	 */
	@Override
	public double valueOf(double[] x) {
		try {
			String levelJson = "["+jsonFromLatentVector(x)+"]";
			Level level = levelFromLatentVector(x);

			EvaluationInfo info = this.marioProcess.simulateOneLevel(level);
			if(info.computeDistancePassed() < LEVEL_LENGTH) { // Did not beat level
				return (double) -info.computeDistancePassed()/LEVEL_LENGTH;
			} else{ // Did beat level
                return (double) -info.computeDistancePassed()/LEVEL_LENGTH - evaluateFitnessMetric(levelJson);
			}

		} catch (IOException e) {
			// Error occurred
			e.printStackTrace();
			System.exit(1);
			return Double.NaN;
		}
	}

	@Override
	public boolean isFeasible(double[] x) {
		return true;
	}

	/**
	 * Map the value in R to (-1, 1)
	 * @param valueInR
	 * @return
	 */
	public static double mapToOne(double valueInR) {
		return ( valueInR / Math.sqrt(1+valueInR*valueInR) );
	}

	public static double[] mapArrayToOne(double[] arrayInR) {
		double[] newArray = new double[arrayInR.length];
		for(int i=0; i<newArray.length; i++) {
			double valueInR = arrayInR[i];
			newArray[i] = mapToOne(valueInR);
                        //System.out.println(valueInR);
		}
		return newArray;
	}
}
