package net.tfminecraft.rpcharacters.objects;

import java.util.ArrayList;
import java.util.List;

public class Question {
	private String question;
	private List<String> answers = new ArrayList<>();
	
	public Question(String s, List<String> l) {
		this.question = s;
		this.answers = l;
	}
	
	public String getQuestion() {
		return question;
	}
	
	public boolean isCorrect(String s) {
		for(String a : answers) {
			if(a.equalsIgnoreCase(s)) return true;
		}
		return false;
	}
	
}
