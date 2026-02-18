package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.ConcurrentHashMap;

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

	private Logger logger = LoggerFactory.getLogger(TourGuideService.class);

	private final GpsUtil gpsUtil;
	private final RewardsService rewardsService;
	private final TripPricer tripPricer = new TripPricer();
	public final Tracker tracker;

	//Pas de tracking auto
	boolean testMode = true;

	private final Map<String, User> internalUserMap = new HashMap<>();

	public RewardsService getRewardsService() {
		return rewardsService;
	}

	public TourGuideService(GpsUtil gpsUtil, RewardsService rewardsService) {
		this.gpsUtil = gpsUtil;
		this.rewardsService = rewardsService;
		
		Locale.setDefault(Locale.US);

		if (testMode) {
			logger.info("TestMode enabled");
			logger.debug("Initializing users");
			initializeInternalUsers();
			logger.debug("Finished initializing users");
		}
		tracker = new Tracker(this);

		//En test, pas de tracker
		if (!testMode) {
			tracker.startTracking();
			addShutDownHook();
		}
	}

	public List<UserReward> getUserRewards(User user) {
		rewardsService.awaitPendingRewards(user); //attends que les rewards soient prete
		return user.getUserRewards();
	}

	public VisitedLocation getUserLocation(User user) {
		VisitedLocation visitedLocation = (user.getVisitedLocations().size() > 0) ? user.getLastVisitedLocation()
				: trackUserLocation(user);
		return visitedLocation;
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
		int cumulatativeRewardPoints = user.getUserRewards().stream().mapToInt(i -> i.getRewardPoints()).sum();
		List<Provider> providers = tripPricer.getPrice(tripPricerApiKey, user.getUserId(),
				user.getUserPreferences().getNumberOfAdults(), user.getUserPreferences().getNumberOfChildren(),
				user.getUserPreferences().getTripDuration(), cumulatativeRewardPoints);
		user.setTripDeals(providers);
		return providers;
	}

	/**
	 * Track location => récupère position GPS, l’ajoute, puis calcule rewards.
	 * Notre RewardsService ne bloque pas => gain énorme.
	 */
	public VisitedLocation trackUserLocation(User user) {
		VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
		user.addToVisitedLocations(visitedLocation);
		// Planifie le calcul (non bloquant), les points seront garantis à la lecture
		rewardsService.calculateRewards(user);
		return visitedLocation;
	}

	/**
	 * Renvoyer les 5 attractions les plus proches
	 */
	public List<Attraction> getNearByAttractions(VisitedLocation visitedLocation) {
		Location userLocation = visitedLocation.location;

		//Utilise le cache du RewardsService
		List<Attraction> nearby = new ArrayList<>(rewardsService.getAttractions());

		//Tri par distance croissante
		nearby.sort((a1, a2) -> {
			double d1 = rewardsService.getDistance(a1, userLocation);
			double d2 = rewardsService.getDistance(a2, userLocation);
			return Double.compare(d1, d2);
		});
		//Retourne uniquement les 5 plus proches
		return (nearby.size() > 5) ? nearby.subList(0, 5) : nearby;
	}

	private void addShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				tracker.stopTracking();
			}
		});
	}

	/**********************************************************************************
	 * 
	 * Methods Below: For Internal Testing
	 * 
	 **********************************************************************************/
	private static final String tripPricerApiKey = "test-server-api-key";
	// Database connection will be used for external users, but for testing purposes
	// internal users are provided and stored in memory
//	private final Map<String, User> internalUserMap = new ConcurrentHashMap<>();

	private void initializeInternalUsers() {
		int n = InternalTestHelper.getInternalUserNumber();
		boolean perfMode = n >= 10_000;

		for (int i = 0; i < n; i++) {
			String userName = "internalUser" + i;
			String phone = "000";
			String email = userName + "@tourGuide.com";
			User user = new User(UUID.randomUUID(), userName, phone, email);

			if (perfMode) {

				user.addToVisitedLocations(new VisitedLocation(
						user.getUserId(),
						new Location(0.0, 0.0),
						new Date()
				));
			} else {
				generateUserLocationHistory(user);
			}

			internalUserMap.put(userName, user);
		}

		logger.debug("Created " + n + " internal test users. perfMode=" + perfMode);
	}

	private void generateUserLocationHistory(User user) {
		IntStream.range(0, 3).forEach(i -> {
			user.addToVisitedLocations(new VisitedLocation(user.getUserId(),
					new Location(generateRandomLatitude(), generateRandomLongitude()), getRandomTime()));
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
		LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
		return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
	}

}
