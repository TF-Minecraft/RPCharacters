package net.tfminecraft.rpcharacters.creation.stages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.objects.Question;
import net.tfminecraft.rpcharacters.utils.RPTexts;

public class QuestionStage extends Stage{
	private List<Question> base = new ArrayList<>();
	private List<Question> questions = new ArrayList<>();
	
	private int amount;
	private int currentQuestion;
	private boolean awaitingNextQuestion;
	private BukkitTask questionTask;
	
	public QuestionStage(Stage s, ConfigurationSection config) {
		copyBaseFields(s);
		Set<String> set = config.getConfigurationSection("questions").getKeys(false);

		List<String> list = new ArrayList<String>(set);
		amount = config.getInt("amount", 1);
		for(String key : list) {
			base.add(new Question(config.getConfigurationSection("questions."+key).getString("question"), config.getConfigurationSection("questions."+key).getStringList("answers")));
		}
		currentQuestion = 0;
	}
	public QuestionStage(QuestionStage another) {
		copyBaseFields(another);
		setStored(another.getQuestions());
		setCurrentQuestion(0);
	}
	public int getAmount() {
		return amount;
	}

	public void pick() {
		// Defensive check: if amount > base size, limit it
		int pickAmount = Math.min(amount, base.size());

		// Create a copy of base to shuffle
		List<Question> shuffled = new ArrayList<>(base);
		
		// Shuffle the copy randomly
		Collections.shuffle(shuffled, new Random());

		// Pick the first N elements after shuffle
		questions = new ArrayList<>(shuffled.subList(0, pickAmount));
	}

	public List<Question> getQuestions() {
		return questions;
	}
	public void setStored(List<Question> questions) {
		base = questions;
	} 
	public void setQuestions(List<Question> questions) {
		this.questions = questions;
	}
	
	public int getCurrentQuestion() {
		return currentQuestion;
	}
	public void setCurrentQuestion(int currentQuestion) {
		this.currentQuestion = currentQuestion;
	}
	public void execute(Player p, CharacterCreation cc) {
		if(cc.isCancelled()) return;
		if (questionTask != null) {
			questionTask.cancel();
			questionTask = null;
		}
		if(currentQuestion >= questions.size()) {
			if(autoNext()) {
				cc.runStage();
			} else {
				cc.setCanNext(true);
			}
			return;
		}
		awaitingNextQuestion = false;
		Question q = questions.get(currentQuestion);
		RPTexts.title(p, RPTexts.SUCCESS + "Question " + (currentQuestion + 1),
				RPTexts.formatGui(q.getQuestion()), 5, 60, 5);
	}
	
	public void checkAnswer(String m, Player p, CharacterCreation cc) {
		if (cc.isCancelled() || cc.getActiveStage() != this) return;
		if (awaitingNextQuestion) {
			RPTexts.send(p, RPTexts.MUTED + "Answer saved. Wait for the next prompt.");
			return;
		}
		if(currentQuestion >= questions.size()) {
			return;
		}
		Question q = questions.get(currentQuestion);
		if(q.isCorrect(m)) {
			RPTexts.title(p, RPTexts.SUCCESS + "Correct!", " ", 2, 16, 2);
			currentQuestion++;
			awaitingNextQuestion = true;
			questionTask = new BukkitRunnable()
			{
				public void run()
				{
					if (cc.isCancelled() || cc.getActiveStage() != QuestionStage.this
							|| CreationManager.activeCreators.get(p) != cc) return;
					execute(p, cc);
				}
			}.runTaskLater(RPCharacters.plugin, 20L);
		} else {
			RPTexts.send(p, RPTexts.ERROR + "Wrong answer.");
		}
	}
}
