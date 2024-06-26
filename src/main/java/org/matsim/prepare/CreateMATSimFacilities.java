package org.matsim.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.TopologyException;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.facilities.*;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@CommandLine.Command(
		name = "facilities",
		description = "Creates MATSim facilities from shape-file and network"
)
public class CreateMATSimFacilities implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(CreateMATSimFacilities.class);

	/**
	 * Filter link types that don't have a facility associated.
	 */
	public static final Set<String> IGNORED_LINK_TYPES = Set.of("motorway", "trunk",
			"motorway_link", "trunk_link", "secondary_link", "primary_link");

	@CommandLine.Option(names = "--network", required = true, description = "Path to car network")
	private Path network;

	@CommandLine.Option(names = "--output", required = true, description = "Path to output facility file")
	private Path output;

	@CommandLine.Mixin
	private ShpOptions shp;

	public static void main(String[] args) {
		new CreateMATSimFacilities().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		if (shp.getShapeFile() == null) {
			log.error("Shp file with facilities is required.");
			return 2;
		}

		Network completeNetwork = NetworkUtils.readNetwork(this.network.toString());
		TransportModeNetworkFilter filter = new TransportModeNetworkFilter(completeNetwork);
		Network carOnlyNetwork = NetworkUtils.createNetwork();
		filter.filter(carOnlyNetwork, Set.of(TransportMode.car));

		List<SimpleFeature> fts = shp.readFeatures();

		Map<Id<Link>, Holder> data = new ConcurrentHashMap<>();

		fts.parallelStream().forEach(ft -> processFeature(ft, carOnlyNetwork, data));

		ActivityFacilities facilities = FacilitiesUtils.createActivityFacilities();

		ActivityFacilitiesFactory f = facilities.getFactory();

		for (Map.Entry<Id<Link>, Holder> e : data.entrySet()) {

			Holder h = e.getValue();

			Id<ActivityFacility> id = Id.create(String.join("_", h.ids), ActivityFacility.class);

			// Create mean coordinate
			OptionalDouble x = h.coords.stream().mapToDouble(Coord::getX).average();
			OptionalDouble y = h.coords.stream().mapToDouble(Coord::getY).average();

			if (x.isEmpty() || y.isEmpty()) {
				log.warn("Empty coordinate (Should never happen)");
				continue;
			}

			ActivityFacility facility = f.createActivityFacility(id, CoordUtils.round(new Coord(x.getAsDouble(), y.getAsDouble())));
			for (String act : h.activities) {
				facility.addActivityOption(f.createActivityOption(act));
			}

			facilities.addActivityFacility(facility);
		}

		log.info("Created {} facilities, writing to {}", facilities.getFacilities().size(), output);

		FacilitiesWriter writer = new FacilitiesWriter(facilities);
		writer.write(output.toString());

		return 0;
	}

	/**
	 * Sample points and choose link with the nearest points. Aggregate everything so there is at most one facility per link.
	 */
	private void processFeature(SimpleFeature ft, Network network, Map<Id<Link>, Holder> data) {

		// Actual id is the last part
		String[] id = ft.getID().split("\\.");

		// Pairs of coords and corresponding links
		List<Coord> coords = samplePoints((MultiPolygon) ft.getDefaultGeometry(), 23);
		List<Id<Link>> links = coords.stream().map(coord -> NetworkUtils.getNearestLinkExactly(network, coord).getId()).toList();

		Map<Id<Link>, Long> map = links.stream()
				.filter(l -> !IGNORED_LINK_TYPES.contains(NetworkUtils.getType(network.getLinks().get(l))))
				.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

		// Everything could be filtered and map empty
		if (map.isEmpty())
			return;

		List<Map.Entry<Id<Link>, Long>> counts = map.entrySet().stream().sorted(Map.Entry.comparingByValue())
				.toList();

		// The "main" link of the facility
		Id<Link> link = counts.get(counts.size() - 1).getKey();

		Holder holder = data.computeIfAbsent(link, k -> new Holder(ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), Collections.synchronizedList(new ArrayList<>())));

		holder.ids.add(id[id.length - 1]);
		holder.activities.addAll(activities(ft));

		// Search for the original drawn coordinate of the associated link
		for (int i = 0; i < links.size(); i++) {
			if (links.get(i).equals(link)) {
				holder.coords.add(coords.get(i));
				break;
			}
		}
	}

	/**
	 * Sample coordinates within polygon.
	 */
	private List<Coord> samplePoints(MultiPolygon geometry, int n) {

		SplittableRandom rnd = new SplittableRandom();

		List<Coord> result = new ArrayList<>();
		Envelope bbox = geometry.getEnvelopeInternal();
		int max = n * 10;
		for (int i = 0; i < max && result.size() < n; i++) {

			Coord coord = CoordUtils.round(new Coord(
					bbox.getMinX() + (bbox.getMaxX() - bbox.getMinX()) * rnd.nextDouble(),
					bbox.getMinY() + (bbox.getMaxY() - bbox.getMinY()) * rnd.nextDouble()
			));

			try {
				if (geometry.contains(MGC.coord2Point(coord))) {
					result.add(coord);
				}
			} catch (TopologyException e) {
				if (geometry.getBoundary().contains(MGC.coord2Point(coord))) {
					result.add(coord);
				}
			}

		}

		if (result.isEmpty())
			result.add(MGC.point2Coord(geometry.getCentroid()));

		return result;
	}

	private Set<String> activities(SimpleFeature ft) {
		Set<String> act = new HashSet<>();

		if (Boolean.TRUE == ft.getAttribute("work")) {
			act.add("work");
			act.add("work_business");
		}
		if (Boolean.TRUE == ft.getAttribute("shop")) {
			act.add("shop_other");
		}
		if (Boolean.TRUE == ft.getAttribute("shop_daily")) {
			act.add("shop_other");
			act.add("shop_daily");
		}
		if (Boolean.TRUE == ft.getAttribute("leisure"))
			act.add("leisure");
		if (Boolean.TRUE == ft.getAttribute("dining"))
			act.add("dining");
		if (Boolean.TRUE == ft.getAttribute("edu_higher"))
			act.add("edu_higher");
		if (Boolean.TRUE == ft.getAttribute("edu_prim")) {
			act.add("edu_primary");
			act.add("edu_secondary");
		}
		if (Boolean.TRUE == ft.getAttribute("edu_kiga"))
			act.add("edu_kiga");
		if (Boolean.TRUE == ft.getAttribute("edu_other"))
			act.add("edu_other");
		if (Boolean.TRUE == ft.getAttribute("p_business") || Boolean.TRUE == ft.getAttribute("medical") || Boolean.TRUE == ft.getAttribute("religious")) {
			act.add("personal_business");
			act.add("work_business");
		}

		return act;
	}

	/**
	 * Temporary data holder for facilities.
	 */
	private record Holder(Set<String> ids, Set<String> activities, List<Coord> coords) {

	}

}
