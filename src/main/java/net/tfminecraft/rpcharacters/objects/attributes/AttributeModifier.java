package net.tfminecraft.rpcharacters.objects.attributes;

public class AttributeModifier {
	private String type;
	private int amount;
	
	public AttributeModifier(String t, int a) {
		this.type = t;
		this.amount = a;
	}
	public AttributeModifier(String s) {
		this.type = s.split("\\.")[0];
		this.amount = Integer.parseInt(s.split("\\.")[1]);
	}

	public String getType() {
		return type;
	}
	
	public int getAmount() {
		return amount;
	}

	public void add(int amount) {
		this.amount = this.amount+amount;
	}
	
	public void remove(int amount) {
		this.amount = this.amount-amount;
		if(this.amount < 0) this.amount = 0;
	}
}
