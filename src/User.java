
public class User {

	public static int PROGRESSIVE_ID = 1;
	
	private int id;
	private String username;
	private String password;
	private int reviews;
	private ExperienceLevel badge;

	public User(int id, String username, String password) {
		this.id = id;
		this.username = username;
		this.password = password;
		this.reviews = 0;
		this.badge = ExperienceLevel.Recensore;
	}

	public User(String username, String password) {
		this.id = PROGRESSIVE_ID++;
		this.username = username;
		this.password = password;
		this.badge = ExperienceLevel.Recensore;
	}

	public int getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}

	public int getReviews() {
		return reviews;
	}
	
	public String getBadge() {
		return badge.toString();
	}
	
	public void addReview() {
		reviews++;
		updateExperienceLevel();
	}
	
	public void updateExperienceLevel() {
		if (reviews < 10)
			badge = ExperienceLevel.Recensore;
		else if (reviews < 20)
			badge = ExperienceLevel.RecensoreEsperto;
		else if (reviews < 30)
			badge = ExperienceLevel.Contributore;
		else if (reviews < 40)
			badge = ExperienceLevel.ContributoreEsperto;
		else
			badge = ExperienceLevel.ContributoreSuper;
	}
	
}
