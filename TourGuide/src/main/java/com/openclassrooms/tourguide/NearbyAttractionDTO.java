package com.openclassrooms.tourguide;

public class NearbyAttractionDTO {

    public String attractionName;
    public double attractionLatitude;
    public double attractionLongitude;

    public double userLatitude;
    public double userLongitude;

    public double distance;
    public int rewardPoints;

    public NearbyAttractionDTO(
            String attractionName,
            double attractionLatitude,
            double attractionLongitude,
            double userLatitude,
            double userLongitude,
            double distance,
            int rewardPoints
    ) {
        this.attractionName = attractionName;
        this.attractionLatitude = attractionLatitude;
        this.attractionLongitude = attractionLongitude;
        this.userLatitude = userLatitude;
        this.userLongitude = userLongitude;
        this.distance = distance;
        this.rewardPoints = rewardPoints;
    }
}