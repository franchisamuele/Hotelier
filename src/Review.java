import java.util.Date;

public class Review {

	private int userId;
	private int hotelId;
	private float rate;
	private Rating ratings;
	private Date date;
	
	public Review(int userId, int hotelId, float rate, Rating ratings, Date date) {
		this.userId = userId;
		this.hotelId = hotelId;
		this.rate = rate;
		this.ratings = ratings;
		this.date = date;
	}

	public int getUserId() {
		return userId;
	}
	
	public int getHotelId() {
		return hotelId;
	}

	public float getRate() {
		return rate;
	}
	
	public float getCleaningRate() {
		return ratings.getCleaning();
	}

	public float getPositionRate() {
		return ratings.getPosition();
	}

	public float getServicesRate() {
		return ratings.getServices();
	}

	public float getQualityRate() {
		return ratings.getQuality();
	}
	
	public Date getDate() {
		return date;
	}
	
}
