package com.openclassrooms.tourguide.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger logger = LoggerFactory.getLogger(RewardsService.class);
	private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

	// proximity in miles
	private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;

	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;

	// Expose les points pour le controller NearbyAttractions
	public int getAttractionRewardPoints(Attraction attraction, User user) {
		return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
	}

	// Cache attractions pour réduire le coût gpsUtil.getAttractions()
	private volatile List<Attraction> cachedAttractions;

	// Retourne la liste des attractions à partir du cache
	public List<Attraction> getAttractions() {
		return getAttractionsCached();
	}

	// Cache attractions, charge le gpsUtil.getAttractions() une fois
	private List<Attraction> getAttractionsCached() {
		List<Attraction> local = cachedAttractions;
		if (local == null) {
			synchronized (this) {
				if (cachedAttractions == null) {
					cachedAttractions = gpsUtil.getAttractions();
				}
				local = cachedAttractions;
			}
		}
		return local;
	}

	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}

	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}


	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
	}

	//Cache interne utilisé dans le calcul batch des rewards
	private final java.util.concurrent.ConcurrentHashMap<UUID, Integer> rewardPointsByAttraction =
			new java.util.concurrent.ConcurrentHashMap<>();


	private int getCachedRewardPoints(UUID attractionId, UUID userId) {
		return rewardPointsByAttraction.computeIfAbsent(
				attractionId,
				id -> rewardsCentral.getAttractionRewardPoints(id, userId)
		);
	}


	// Calcule les rewards pour un user
	public void calculateRewards(User user) {

		// Création de snapshots protégés pour éviter les concurrences
		final List<VisitedLocation> visitedLocationsSnapshot;
		final List<UserReward> userRewardsSnapshot;

		synchronized (user) {
			List<VisitedLocation> visitedLocations = user.getVisitedLocations();
			if (visitedLocations == null || visitedLocations.isEmpty()) {
				return;
			}

			// Copie locale pour éviter qu'une modification concurrente n'impacte le calcul
			visitedLocationsSnapshot = new ArrayList<>(visitedLocations);

			List<UserReward> currentRewards = user.getUserRewards();
			userRewardsSnapshot = (currentRewards == null)
					? new ArrayList<>()
					: new ArrayList<>(currentRewards);
		}

		// Récupération de la liste d'attractions depuis le cache
		final List<Attraction> attractions = getAttractionsCached();
		if (attractions == null || attractions.isEmpty()) {
			return;
		}

		//Set local des attractions déjà récompensées, évite les doublons
		final HashSet<UUID> rewardedAttractionIds = new HashSet<>(Math.max(16, userRewardsSnapshot.size() * 2));
		for (UserReward reward : userRewardsSnapshot) {
			rewardedAttractionIds.add(reward.attraction.attractionId);
		}

		for (VisitedLocation visitedLocation : visitedLocationsSnapshot) {
			for (Attraction attraction : attractions) {

				UUID attractionId = attraction.attractionId;

				// Déjà récompensé
				if (rewardedAttractionIds.contains(attractionId)) {
					continue;
				}

				// Trop loin
				if (!nearAttraction(visitedLocation, attraction)) {
					continue;
				}

				// Appel potentiellement lent -> version batch avec cache
				int rewardPoints = getCachedRewardPoints(attractionId, user.getUserId());

				// Ajout protégé pour gérer la concurrence et éviter un doublon final
				synchronized (user) {
					boolean alreadyRewarded = false;
					for (UserReward existing : user.getUserRewards()) {
						if (existing.attraction.attractionId.equals(attractionId)) {
							alreadyRewarded = true;
							break;
						}
					}

					if (!alreadyRewarded) {
						user.addUserReward(new UserReward(visitedLocation, attraction, rewardPoints));
					}
				}

				rewardedAttractionIds.add(attractionId);
			}
		}
	}

	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) > attractionProximityRange ? false : true;
	}

	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		Location u = visitedLocation.location;

		// préfiltre grossier (en degrés) avant trig
		// 1 degré de latitude ~ 69 miles
		double latDiff = Math.abs(attraction.latitude - u.latitude);
		if (latDiff > (proximityBuffer / 69.0)) return false;

		double lonDiff = Math.abs(attraction.longitude - u.longitude);
		if (lonDiff > (proximityBuffer / 69.0)) return false;

		return getDistance(attraction, u) <= proximityBuffer;
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
