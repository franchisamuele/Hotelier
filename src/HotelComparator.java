import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public class HotelComparator implements Comparator<Hotel> {

	@Override
	public int compare(Hotel o1, Hotel o2) {
		// Ordinamento decrescente per la classifica
		if (calculateRanking(o2) - calculateRanking(o1) < 0)
			return -1;
		else
			return 1;
	}
	
	private double calculateRanking(Hotel h) {
		double ratingWeight = h.rate * 0.4 + h.getCleaningRate() * 0.15 + h.getPositionRate() * 0.15 + h.getQualityRate() * 0.15 + h.getServicesRate() * 0.15;
		double reviewsWeight = Math.log10(h.reviews() + 1);
		double actualityRate;
		
		if (h.getAvgReviewDate() == null)
			actualityRate = 0;
		else {
			long daysFromAvgDate = TimeUnit.DAYS.convert( (new Date()).getTime() - h.getAvgReviewDate().getTime(), TimeUnit.MILLISECONDS );
			actualityRate = Math.exp(-0.01 * daysFromAvgDate);
		}
		
		return (ratingWeight * 0.6) + (reviewsWeight * 0.05) + (actualityRate * 0.35);
	}

}
