/*
 * Copyright (c) 2026, Dewdul
 * All rights reserved.
 * Licensed under the BSD 2-Clause License. See LICENSE.
 */
package com.spirittreeatlas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import lombok.Getter;
import lombok.Setter;

/**
 * One entry of rings.json. Gson fills the fields; the repository then patches the position and
 * the travel-log row from the game's own DB table.
 */
@Getter
public class Ring
{
	public static final String KIND_DESTINATION = "destination";
	private static final java.util.regex.Pattern WORD_BREAK = java.util.regex.Pattern.compile("[^a-z0-9]+");

	private String code;
	private String kind = KIND_DESTINATION;
	private String name;
	private String description;
	@Setter
	private int x;
	@Setter
	private int y;
	@Setter
	private int plane;
	@Setter
	private String layer;
	@Setter
	private String area;
	private List<String> requirements;
	private List<String> danger;
	private List<String> poi;
	private List<String> tags;
	private List<String> notes;
	private int logVarbit = -1;
	private boolean noStaffReturn;
	/** What must hold before a first visit can unlock the ring (UnlockCheck); null when nothing. */
	private List<Condition> unlock;

	/** Packed widget id of this code's travel log row, from the DB table; -1 until read. */
	@Setter
	private transient int rowComponent = -1;
	/** Packed widget id of the star beside the row; -1 until read. */
	@Setter
	private transient int starComponent = -1;
	private transient List<String> words;

	public Ring()
	{
	}

	Ring(String code, String name, int x, int y, String layer)
	{
		this.code = code;
		this.name = name;
		this.x = x;
		this.y = y;
		this.layer = layer;
	}

	/** A real dial target: a destination with a three-letter code. */
	public boolean isDialable()
	{
		return KIND_DESTINATION.equals(kind) && DialMath.isCode(code);
	}

	/** Whether the ring has a place on some map layer (everything but the house). */
	public boolean isMapped()
	{
		return layer != null && !Layer.POH.equals(layer) && (x != 0 || y != 0);
	}

	public String getName()
	{
		return name == null ? (code == null ? "?" : code) : name;
	}

	public List<String> getRequirements()
	{
		return nonNull(requirements);
	}

	public List<String> getDanger()
	{
		return nonNull(danger);
	}

	public List<String> getPoi()
	{
		return nonNull(poi);
	}

	public List<String> getNotes()
	{
		return nonNull(notes);
	}

	public List<Condition> getUnlock()
	{
		return unlock == null ? Collections.emptyList() : unlock;
	}

	/**
	 * One unlock condition from rings.json: a quest state, a varbit or varp comparison, or a need
	 * the client cannot see ("unknown"). The label reads after "needs".
	 */
	@Getter
	public static class Condition
	{
		private String type;
		private String quest;
		private String state;
		private int id;
		private String op;
		private int value;
		private String label;

		public Condition()
		{
		}

		Condition(String type, String quest, String state, int id, String op, int value, String label)
		{
			this.type = type;
			this.quest = quest;
			this.state = state;
			this.id = id;
			this.op = op;
			this.value = value;
			this.label = label;
		}
	}

	/**
	 * Whether the ring matches the travel log search. Up to three letters ("c", "cl", "clr", or
	 * "c l r") are a code: only codes beginning with them match. Longer searches match when every
	 * word typed is the start of a word in the ring's name, area, nearby places or tags:
	 * "zulrah", "kalph", "ice mount".
	 */
	public boolean matches(String query)
	{
		return matches(query, null);
	}

	/**
	 * {@link #matches(String)} for a row that shows the ring under its own label (a group's row):
	 * the label's words count as the ring's words. Up to three letters still match the code only.
	 */
	public boolean matches(String query, String label)
	{
		if (query == null || query.trim().isEmpty())
		{
			return true;
		}
		String q = query.trim().toLowerCase(Locale.ROOT);
		String compact = q.replace(" ", "");
		if (compact.length() <= 3)
		{
			return code != null && code.toLowerCase(Locale.ROOT).startsWith(compact);
		}
		if (words == null)
		{
			List<String> w = new ArrayList<>();
			addWords(w, code);
			addWords(w, name);
			addWords(w, area);
			for (String p : getPoi())
			{
				addWords(w, p);
			}
			for (String t : nonNull(tags))
			{
				addWords(w, t);
			}
			words = w;
		}
		List<String> labelWords = new ArrayList<>();
		addWords(labelWords, label);
		for (String part : WORD_BREAK.split(q))
		{
			if (!part.isEmpty() && !startsAWord(words, part) && !startsAWord(labelWords, part))
			{
				return false;
			}
		}
		return true;
	}

	/** Lower-case words, split at anything that is not a letter or digit. */
	static void addWords(List<String> out, String text)
	{
		if (text == null)
		{
			return;
		}
		for (String w : WORD_BREAK.split(text.toLowerCase(Locale.ROOT)))
		{
			if (!w.isEmpty())
			{
				out.add(w);
			}
		}
	}

	static boolean startsAWord(List<String> words, String prefix)
	{
		for (String w : words)
		{
			if (w.startsWith(prefix))
			{
				return true;
			}
		}
		return false;
	}

	void resetSearchCache()
	{
		words = null;
	}

	private static List<String> nonNull(List<String> list)
	{
		return list == null ? Collections.emptyList() : list;
	}
}
