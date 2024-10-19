import java.util.Date;

public class Hotel {

	private int id;
	public String name;
	public String description;
	public String city;
	public String phone;
	public String services[];
	public float rate;
	private Rating ratings;
	private int reviews;
	private Date avgReviewDate;
	
	public Hotel(int id, String name, String description, String city, String phone, String[] services, float rate, Rating ratings, int reviews) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.city = city;
		this.phone = phone;
		this.services = services;
		this.rate = rate;
		this.ratings = ratings;
		this.reviews = reviews;
		this.avgReviewDate = null;
	}
	
	@Override
	public boolean equals(Object obj) {
	    if (this == obj)
	        return true;
	    
	    if (obj == null || getClass() != obj.getClass())
	        return false;
	    
	    Hotel compared = (Hotel) obj;
	    return this.id() == compared.id();
	}

	public int id() {
		return id;
	}
	
	public int reviews() {
		return reviews;
	}

	public float getCleaningRate() {
		return ratings.getCleaning();
	}
	
	public void setCleaningRate(float cleaningRate) {
		ratings.setCleaning(cleaningRate);
	}

	public float getPositionRate() {
		return ratings.getPosition();
	}
	
	public void setPositionRate(float positionRate) {
		ratings.setPosition(positionRate);;
	}

	public float getServicesRate() {
		return ratings.getServices();
	}
	
	public void setServicesRate(float servicesRate) {
		ratings.setServices(servicesRate);
	}

	public float getQualityRate() {
		return ratings.getQuality();
	}
	
	public void setQualityRate(float qualityRate) {
		ratings.setQuality(qualityRate);
	}
	
	public Date getAvgReviewDate() {
		return avgReviewDate;
	}
	
	// Aggiorna tutte le medie dei parametri
	public void newReview(Review review) {
		if (avgReviewDate == null)
			avgReviewDate = review.getDate();
		else {
			long avgTimestamp = avgReviewDate.getTime();
			long currTimestamp = review.getDate().getTime();
			
			avgTimestamp = (avgTimestamp * reviews + currTimestamp) / (reviews + 1);
			avgReviewDate = new Date(avgTimestamp);
		}
		
		this.rate = (this.rate * this.reviews + review.getRate()) / (this.reviews + 1);
		this.setCleaningRate( (this.getCleaningRate() * this.reviews + review.getCleaningRate()) / (this.reviews + 1) );
		this.setPositionRate( (this.getPositionRate() * this.reviews + review.getPositionRate()) / (this.reviews + 1) );
		this.setServicesRate( (this.getServicesRate() * this.reviews + review.getServicesRate()) / (this.reviews + 1) );
		this.setQualityRate( (this.getQualityRate() * this.reviews + review.getQualityRate()) / (this.reviews + 1) );
		
		this.reviews++;
	}
	
}
