package com.openclassrooms.tourguide.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

	// proximity in miles
    private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;

	private final ExecutorService rewardsExecutor =
			Executors.newFixedThreadPool(100);

	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
	}


	 // Getter pour controller getNearbyAttractions pour accéder à AttractionReward

	public int getAttractionRewardPoints(Attraction attraction, User user) {
		return rewardsCentral.getAttractionRewardPoints(
				attraction.attractionId,
				user.getUserId()
		);
	}

	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}
	
	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

	public void calculateRewards(User user) {

		// On verrouille l'utilisateur pendant la préparation (lecture des listes)
		final List<VisitedLocation> userLocations;
		final List<Attraction> attractions;
		final Set<UUID> rewardedAttractionIds;

		//Verrouille le user, evite concurrence
		synchronized (user) {
			userLocations = new ArrayList<>(user.getVisitedLocations()); //copie
			attractions = gpsUtil.getAttractions();

			rewardedAttractionIds = user.getUserRewards().stream()
					.map(r -> r.attraction.attractionId)
					.collect(Collectors.toSet());
		}

		// Préparer les tâches à exécuter (sans faire d'appel réseau)
		List<Callable<UserReward>> tasks = new ArrayList<>();

		for (VisitedLocation visitedLocation : userLocations) {
			for (Attraction attraction : attractions) {
				if (!rewardedAttractionIds.contains(attraction.attractionId)
						&& nearAttraction(visitedLocation, attraction)) {

					tasks.add(() -> {    //Récupère locations, attractions, points
						int points = rewardsCentral.getAttractionRewardPoints(
								attraction.attractionId,
								user.getUserId()
						);
						return new UserReward(visitedLocation, attraction, points);
					});

					rewardedAttractionIds.add(attraction.attractionId);
				}
			}
		}

		// Exécution en parallèle au maximum de threads accepté
		List<Future<UserReward>> futures;
		try {
			futures = rewardsExecutor.invokeAll(tasks);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}

		// Ajout userReward calculé dans l'utilisateur
		synchronized (user) {
			for (Future<UserReward> f : futures) {
				try {
					UserReward reward = f.get();
					if (reward != null) {
						user.addUserReward(reward);
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				} catch (ExecutionException e) {
				}
			}
		}
	}
	
	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) > attractionProximityRange ? false : true;
	}
	
	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return getDistance(attraction, visitedLocation.location) > proximityBuffer ? false : true;
	}
	
	private int getRewardPoints(Attraction attraction, User user) {
		return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
	}
	
	public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                               + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
        double statuteMiles = STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
        return statuteMiles;
	}

}
