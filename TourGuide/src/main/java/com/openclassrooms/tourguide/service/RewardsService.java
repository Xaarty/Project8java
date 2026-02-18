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

	/*
	 * Thread pool d'exécution des appels RewardCentral en parallèle.
	 * Threads DAEMON permettent de ne pas bloquer les tests en fonction des appels en background.
	 */
	private final ExecutorService rewardPointsExecutor = Executors.newFixedThreadPool(
			Math.max(8, Runtime.getRuntime().availableProcessors() * 4),
			new DaemonThreadFactory("reward-points-")
	);

	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
	}

	// Cache attractions pour réduire le coût gpsUtil.getAttractions()
	private volatile List<Attraction> cachedAttractions;

	// Futures en cours par utilisateur, attendre pour la lecture des rewards).
	private final ConcurrentHashMap<UUID, List<CompletableFuture<Void>>> pendingRewardFutures = new ConcurrentHashMap<>();

	// Expose la liste d’attractions (cache).
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


	 //Permet d'avoir les rewards chargées
	public void awaitPendingRewards(User user) {
		List<CompletableFuture<Void>> list = pendingRewardFutures.remove(user.getUserId());
		if (list == null || list.isEmpty()) return;

		CompletableFuture.allOf(list.toArray(new CompletableFuture[0])).join();
	}

	//Accès Controller (NearbyAttractions)
	public int getAttractionRewardPoints(Attraction attraction, User user) {
		return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
	}

	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}
	
	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

	//Calcule les rewards pour un user
	public void calculateRewards(User user) {

		//Copie des données du user
		final List<VisitedLocation> userLocations;
		final List<Attraction> attractions;
		final Set<UUID> alreadyRewardedAttractionIds;

		synchronized (user) {
			userLocations = new ArrayList<>(user.getVisitedLocations()); //Copie
			if (userLocations.isEmpty()) {
				return;
			}

			attractions = getAttractionsCached();

			// Set avec attractionID, évite check couteux, doublons
			alreadyRewardedAttractionIds = user.getUserRewards().stream()
					.map(r -> r.attraction.attractionId)
					.collect(Collectors.toSet());
		}

		List<CompletableFuture<Void>> futuresForThisCall = new ArrayList<>();

		//Parcours les visitedLocations + attractions
		for (VisitedLocation visitedLocation : userLocations) {
			for (Attraction attraction : attractions) {

				// Déjà récompensé
				if (alreadyRewardedAttractionIds.contains(attraction.attractionId)) {
					continue;
				}

				// Trop loin
				if (!nearAttraction(visitedLocation, attraction)) {
					continue;
				}

				//Crée une reward (placeholder)
				final UserReward placeholder;

				synchronized (user) {
					// Check de l'attraction (Concurence)
					boolean exists = user.getUserRewards().stream()
							.anyMatch(r -> r.attraction.attractionId.equals(attraction.attractionId));

					if (exists) {
						alreadyRewardedAttractionIds.add(attraction.attractionId);
						continue;
					}

					// Placeholder points = 0 : ajout de la reward
					placeholder = new UserReward(visitedLocation, attraction, 0);
					user.addUserReward(placeholder);
					// Ajout de l'attraction pour éviter 2 placeholder
					alreadyRewardedAttractionIds.add(attraction.attractionId);
				}

				// Calcul des points en parallèle, puis mise à jour du placeholder
				CompletableFuture<Void> f = CompletableFuture
						.supplyAsync( //thread du pool rewardPointsExecutor
								() -> rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId()),
								rewardPointsExecutor)
						.thenAccept(points -> { //Attend la disponibilité
							try {
								placeholder.setRewardPoints(points);
							} catch (Exception e) {
								logger.debug("Unable to set reward points on placeholder", e);
							}
						});

				futuresForThisCall.add(f);
			}
		}

		// On enregistre les futures pour pouvoir attendre à la lecture des rewards
		if (!futuresForThisCall.isEmpty()) {
			pendingRewardFutures.merge(  //merge pour concurrence et grouper les appels
					user.getUserId(),
					futuresForThisCall,
					(oldList, newList) -> {
						oldList.addAll(newList);
						return oldList;
					}
			);
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


	 //ThreadFactory daemon = permet aux test de continuer même avec tâches en arriere plan
	private static class DaemonThreadFactory implements ThreadFactory {
		private final String prefix;
		private final AtomicInteger idx = new AtomicInteger(1);

		private DaemonThreadFactory(String prefix) {
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r);
			t.setName(prefix + idx.getAndIncrement());
			t.setDaemon(true);
			return t;
		}
	}
}
