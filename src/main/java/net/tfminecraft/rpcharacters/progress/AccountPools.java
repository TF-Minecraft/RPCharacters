package net.tfminecraft.rpcharacters.progress;

import net.tfminecraft.rpcharacters.objects.PlayerData;

/** The shared account pools, kept while progress is not per character. */
final class AccountPools implements ProgressPools {
	private final PlayerData account;

	AccountPools(PlayerData account) {
		this.account = account;
	}

	@Override
	public String holder() {
		return "account";
	}

	@Override
	public int getSkillPointsTotal() {
		return account.getAccountSkillPointsTotal();
	}

	@Override
	public void setSkillPointsTotal(int total) {
		account.setAccountSkillPointsTotal(total);
	}

	@Override
	public void addSkillPoints(int amount) {
		account.addAccountSkillPoints(amount);
	}

	@Override
	public int getRewardSkillPoints() {
		return account.getAccountRewardSkillPoints();
	}

	@Override
	public void setRewardSkillPoints(int credited) {
		account.setAccountRewardSkillPoints(credited);
	}

	@Override
	public int getAttributePointsTotal() {
		return account.getAccountAttributePointsTotal();
	}

	@Override
	public void setAttributePointsTotal(int total) {
		account.setAccountAttributePointsTotal(total);
	}

	@Override
	public void addAttributePoints(int amount) {
		account.addAccountAttributePoints(amount);
	}

	@Override
	public int getProfessionPoints(String professionId) {
		return account.getAccountProfessionPoints(professionId);
	}

	@Override
	public void addProfessionPoints(String professionId, int amount) {
		account.addAccountProfessionPoints(professionId, amount);
	}
}
