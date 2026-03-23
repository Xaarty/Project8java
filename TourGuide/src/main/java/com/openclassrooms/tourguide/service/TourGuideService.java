package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;

import tripPricer.Provider;
import tripPricer.TripPricer;

@Service
public class TourGuideService {

	private final Logger logger = LoggerFactory.getLogger(TourGuideService.class);

	private final GpsUtil gpsUtil;
	private final RewardsService rewardsService;
	private final TripPricer tripPricer = new TripPricer();
	public final Tracker tracker;

	// Pas de tracking auto
	boolean testMode = true;

	private final Map<String, User> internalUserMap = new HashMap<>();

	private static final int GPS_WINDOW_SIZE = 200;

	// Pool de threads borné pour paralléliser les GPS
	private final ExecutorService gpsExecutor =
			Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 8);

	private final Queue<GpsTask> gpsTasks = new ArrayDeque<>();


	private static class GpsTask {

		User user;
		CompletableFuture<VisitedLocation> future;

		GpsTask(User user, CompletableFuture<VisitedLocation> future) {
			this.user = user;
			this.future = future;
		}
	}


	public RewardsService getRewardsService() {
		return rewardsService;
	}


	public TourGuideService(GpsUtil gpsUtil, RewardsService rewardsService) {

		this.gpsUtil = gpsUtil;
		this.rewardsService = rewardsService;

		Locale.setDefault(Locale.US);

		if (testMode) {
			logger.info("TestMode enabled");
			initializeInternalUsers();
		}

		tracker = new Tracker(this);

		//En test pas de tracker
		if (!testMode) {
			tracker.startTracking();
			addShutDownHook();
		}
	}


	public List<UserReward> getUserRewards(User user) {
		return user.getUserRewards();
	}


	public VisitedLocation getUserLocation(User user) {

		return (user.getVisitedLocations().size() > 0)
				? user.getLastVisitedLocation()
				: trackUserLocation(user);
	}


	public User getUser(String userName) {
		return internalUserMap.get(userName);
	}


	public List<User> getAllUsers() {
		return new ArrayList<>(internalUserMap.values());
	}


	public void addUser(User user) {

		if (!internalUserMap.containsKey(user.getUserName())) {
			internalUserMap.put(user.getUserName(), user);
		}
	}


	public List<Provider> getTripDeals(User user) {

		int cumulatativeRewardPoints = user.getUserRewards().stream()
				.mapToInt(UserReward::getRewardPoints)
				.sum();

		List<Provider> providers = tripPricer.getPrice(
				tripPricerApiKey,
				user.getUserId(),
				user.getUserPreferences().getNumberOfAdults(),
				user.getUserPreferences().getNumberOfChildren(),
				user.getUserPreferences().getTripDuration(),
				cumulatativeRewardPoints
		);

		user.setTripDeals(providers);
		return providers;
	}

	// Determination de la position GPS de l'utilisateur en asynchrone, traite les taches par fenetres
	public VisitedLocation trackUserLocation(User user) {

		//Liste futur pour optimisation en asynchrone
		CompletableFuture<VisitedLocation> future =
				CompletableFuture.supplyAsync(
						() -> gpsUtil.getUserLocation(user.getUserId()),
						gpsExecutor
				);

		gpsTasks.add(new GpsTask(user, future));

		if (gpsTasks.size() >= GPS_WINDOW_SIZE) {
			return processOldestGpsTask();
		}

		// Si il n'y a pas d'historique, on calcule immédiatement la position
		if (user.getVisitedLocations() == null || user.getVisitedLocations().isEmpty()) {

			VisitedLocation visitedLocation = future.join();
			user.addToVisitedLocations(visitedLocation);
			rewardsService.calculateRewards(user);
			return visitedLocation;
		}
		//Retour temporaire, la vraie position est intégrée lors du traitement en différé
		return new VisitedLocation(user.getUserId(), new Location(0, 0), new Date());
	}


	// Traite la plus ancienne tâche GPS en attente
	private VisitedLocation processOldestGpsTask() {

		GpsTask task = gpsTasks.poll();

		VisitedLocation visitedLocation = task.future.join();

		task.user.addToVisitedLocations(visitedLocation);

		rewardsService.calculateRewards(task.user);

		return visitedLocation;
	}

	// S'assure que les calculs GPS sont fini
	public void waitForAllGpsTasks() {

		while (!gpsTasks.isEmpty()) {
			processOldestGpsTask();
		}

		gpsExecutor.shutdown();
	}

	//Correctif : les 5 attractions les plus proches
	public List<Attraction> getNearByAttractions(VisitedLocation visitedLocation) {

		Location userLocation = visitedLocation.location;

		// Utilise le cache du RewardsService
		List<Attraction> nearby = new ArrayList<>(rewardsService.getAttractions());

		// Tri par distance croissante
		nearby.sort((a1, a2) -> {
			double d1 = rewardsService.getDistance(a1, userLocation);
			double d2 = rewardsService.getDistance(a2, userLocation);
			return Double.compare(d1, d2);
		});

		// Retourne uniquement les 5 plus proches
		return (nearby.size() > 5) ? nearby.subList(0, 5) : nearby;
	}



	private void addShutDownHook() {

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			tracker.stopTracking();
			waitForAllGpsTasks();
		}));
	}



	/**********************************************************************************
	 *
	 * Methods Below: For Internal Testing
	 *
	 **********************************************************************************/

	private static final String tripPricerApiKey = "test-server-api-key";


	private void initializeInternalUsers() {

		int n = InternalTestHelper.getInternalUserNumber();

		for (int i = 0; i < n; i++) {

			String userName = "internalUser" + i;

			User user = new User(
					UUID.randomUUID(),
					userName,
					"000",
					userName + "@tourGuide.com"
			);

			generateUserLocationHistory(user);

			internalUserMap.put(userName, user);
		}
	}



	private void generateUserLocationHistory(User user) {

		IntStream.range(0, 3).forEach(i -> {

			user.addToVisitedLocations(
					new VisitedLocation(
							user.getUserId(),
							new Location(generateRandomLatitude(), generateRandomLongitude()),
							getRandomTime()
					)
			);
		});
	}



	private double generateRandomLongitude() {

		double leftLimit = -180;
		double rightLimit = 180;

		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}



	private double generateRandomLatitude() {

		double leftLimit = -85.05112878;
		double rightLimit = 85.05112878;

		return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
	}



	private Date getRandomTime() {

		LocalDateTime localDateTime =
				LocalDateTime.now().minusDays(new Random().nextInt(30));

		return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
	}
}