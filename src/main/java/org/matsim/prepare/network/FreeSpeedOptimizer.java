package org.matsim.prepare.network;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.CommandSpec;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.analysis.traffic.traveltime.SampleValidationRoutes;
import org.matsim.application.options.InputOptions;
import org.matsim.contrib.osm.networkReader.LinkProperties;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.DijkstraFactory;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.core.utils.geometry.CoordUtils;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.DoubleStream;

@CommandLine.Command(
	name = "network-freespeed",
	description = "Start server for freespeed optimization."
)
@CommandSpec(
	requireNetwork = true,
	requires = "features.csv"
)
@Deprecated
public class FreeSpeedOptimizer implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(FreeSpeedOptimizer.class);

	@CommandLine.Mixin
	private InputOptions input = InputOptions.ofCommand(FreeSpeedOptimizer.class);

	@CommandLine.Option(names = "--output", description = "Path to output network")
	private Path output;

	@CommandLine.Option(names = "--params", description = "Apply params and write to output if given")
	private Path params;

	@CommandLine.Parameters(arity = "0..*", description = "Input validation files loaded from APIs")
	private List<String> validationFiles;

	private Network network;
	private Object2DoubleMap<SampleValidationRoutes.FromToNodes> validationSet;
	private Map<Id<Link>, PrepareNetworkParams.Feature> features;

	private ObjectMapper mapper;

	/**
	 * Original speeds.
	 */
	private Object2DoubleMap<Id<Link>> speeds = new Object2DoubleOpenHashMap<>();

	public static void main(String[] args) {
		new FreeSpeedOptimizer().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		// TODO: must be reusable class
		// TODO: evaluate many factors (f) and write results to csv

		network = input.getNetwork();
		mapper = new ObjectMapper();
		mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
		mapper.setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

		for (Link link : network.getLinks().values()) {
			speeds.put(link.getId(), link.getFreespeed());
		}

		validationSet = readValidation(validationFiles);
		features = PrepareNetworkParams.readFeatures(input.getPath("features.csv"), network.getLinks().size());

		log.info("Initial score:");
		evaluateNetwork(null, "init");

		evaluateNetwork(new Request(0.5), "05");
		evaluateNetwork(new Request(0.75), "075");
		evaluateNetwork(new Request(0.9), "09");

		if (output != null && params != null) {
			Request p = mapper.readValue(params.toFile(), Request.class);
			evaluateNetwork(p, null);
			NetworkUtils.writeNetwork(network, output.toString());

			return 0;
		}

		Server server = new Server(9090);

		ServletHandler handler = new ServletHandler();
		server.setHandler(handler);

		handler.addServletWithMapping(new ServletHolder(new Backend()), "/");

		try {
			server.start();
			server.join();
		} finally {
			server.destroy();
		}

		return 0;
	}

	private Result evaluateNetwork(Request request, String save) throws IOException {

		Map<Id<Link>, double[]> attributes = new HashMap<>();

		if (request != null) {
			for (Link link : network.getLinks().values()) {

				double allowedSpeed = NetworkUtils.getAllowedSpeed(link);

				if (request.f == 0) {

					PrepareNetworkParams.Feature ft = features.get(link.getId());
					String type = NetworkUtils.getHighwayType(link);

					if (type.startsWith("motorway")) {
						link.setFreespeed(allowedSpeed);
						continue;
					}

					FeatureRegressor speedModel = switch (ft.junctionType()) {
						case "traffic_light" -> Speedrelative_traffic_light.INSTANCE;
						case "right_before_left" -> Speedrelative_right_before_left.INSTANCE;
						case "priority" -> Speedrelative_priority.INSTANCE;
						default -> throw new IllegalArgumentException("Unknown type: " + ft.junctionType());
					};

					double[] p = switch (ft.junctionType()) {
						case "traffic_light" -> request.traffic_light;
						case "right_before_left" -> request.rbl;
						case "priority" -> request.priority;
						default -> throw new IllegalArgumentException("Unknown type: " + ft.junctionType());
					};

					double speedFactor = Math.max(0.25, speedModel.predict(ft.features(), p));

					attributes.put(link.getId(), speedModel.getData(ft.features()));

					link.setFreespeed((double) link.getAttributes().getAttribute("allowed_speed") * speedFactor);
					link.getAttributes().putAttribute("speed_factor", speedFactor);

				} else
					// Old MATSim freespeed logic
					link.setFreespeed(LinkProperties.calculateSpeedIfSpeedTag(allowedSpeed, request.f));
			}

			if (save != null)
				mapper.writeValue(new File(save + "-params.json"), request);
		}

		FreeSpeedTravelTime tt = new FreeSpeedTravelTime();
		OnlyTimeDependentTravelDisutility util = new OnlyTimeDependentTravelDisutility(tt);
		LeastCostPathCalculator router = new DijkstraFactory(false).createPathCalculator(network, util, tt);

		SummaryStatistics rmse = new SummaryStatistics();
		SummaryStatistics mse = new SummaryStatistics();

		CSVPrinter csv = save != null ? new CSVPrinter(Files.newBufferedWriter(Path.of(save + "-eval.csv")), CSVFormat.DEFAULT) : null;

		if (csv != null)
			csv.printRecord("from_node", "to_node", "beeline_dist", "dist", "travel_time");

		List<Data> priority = new ArrayList<>();
		List<Data> rbl = new ArrayList<>();
		List<Data> traffic_light = new ArrayList<>();

		for (Object2DoubleMap.Entry<SampleValidationRoutes.FromToNodes> e : validationSet.object2DoubleEntrySet()) {

			SampleValidationRoutes.FromToNodes r = e.getKey();

			Node fromNode = network.getNodes().get(r.fromNode());
			Node toNode = network.getNodes().get(r.toNode());
			LeastCostPathCalculator.Path path = router.calcLeastCostPath(fromNode, toNode, 0, null, null);

			// iterate over the path, calc better correction
			double distance = path.links.stream().mapToDouble(Link::getLength).sum();
			double speed = distance / path.travelTime;

			double correction = speed / e.getDoubleValue();

			for (Link link : path.links) {

				if (!attributes.containsKey(link.getId()))
					continue;

				PrepareNetworkParams.Feature ft = features.get(link.getId());
				double[] input = attributes.get(link.getId());
				double speedFactor = (double) link.getAttributes().getAttribute("speed_factor");

				List<Data> category = switch (ft.junctionType()) {
					case "traffic_light" -> traffic_light;
					case "right_before_left" -> rbl;
					case "priority" -> priority;
					default -> throw new IllegalArgumentException("not happening");
				};

				category.add(new Data(input, speedFactor, speedFactor / correction));
			}


			rmse.addValue(Math.pow(e.getDoubleValue() - speed, 2));
			mse.addValue(Math.abs((e.getDoubleValue() - speed) * 3.6));

			if (csv != null)
				csv.printRecord(r.fromNode(), r.toNode(), (int) CoordUtils.calcEuclideanDistance(fromNode.getCoord(), toNode.getCoord()),
					(int) distance, (int) path.travelTime);
		}

		if (csv != null)
			csv.close();

		log.info("{}, rmse: {}, mae: {}", request, rmse.getMean(), mse.getMean());

		return new Result(rmse.getMean(), mse.getMean(), priority, rbl, traffic_light);
	}

	/**
	 * Calculate the target speed.
	 */
	static Object2DoubleMap<SampleValidationRoutes.FromToNodes> readValidation(List<String> validationFiles) throws IOException {

		// entry to hour and list of speeds
		Map<SampleValidationRoutes.FromToNodes, Int2ObjectMap<DoubleList>> entries = SampleValidationRoutes.readValidation(validationFiles);

		Object2DoubleMap<SampleValidationRoutes.FromToNodes> result = new Object2DoubleOpenHashMap<>();

		// Target values
		for (Map.Entry<SampleValidationRoutes.FromToNodes, Int2ObjectMap<DoubleList>> e : entries.entrySet()) {

			Int2ObjectMap<DoubleList> perHour = e.getValue();

			// Use avg from all values for 3:00 and 21:00
			double avg = DoubleStream.concat(perHour.get(3).doubleStream(), perHour.get(21).doubleStream())
				.average().orElseThrow();


			result.put(e.getKey(), avg);
		}

		return result;
	}

	private record Data(double[] x, double yPred, double yTrue) {

	}

	private record Result(double rmse, double mse, List<Data> priority, List<Data> rbl, List<Data> traffic_light) {}


	/**
	 * JSON request containing desired parameters.
	 */
	private static final class Request {

		double[] priority;
		double[] rbl;
		double[] traffic_light;

		double f;

		public Request() {
		}

		public Request(double f) {
			this.f = f;
		}

		@Override
		public String toString() {
			if (f == 0)
				return "Request{" +
					"priority=" + priority.length +
					", rbl=" + rbl.length +
					", traffic_light=" + traffic_light.length +
					'}';

			return "Request{f=" + f + "}";
		}
	}

	private final class Backend extends HttpServlet {

		@Override
		protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {

			Request request = mapper.readValue(req.getInputStream(), Request.class);

			boolean save = req.getRequestURI().equals("/save");
			Result stats = evaluateNetwork(request, save ? "network-opt" : null);

			if (save)
				NetworkUtils.writeNetwork(network, "network-opt.xml.gz");

			resp.setStatus(200);

			PrintWriter writer = resp.getWriter();

			mapper.writeValue(writer, stats);

			writer.close();
		}
	}

}
