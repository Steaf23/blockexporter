package com.github.kazuofficial.blockexporter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.resources.Identifier;

public class AtlasWriter {
	public static JsonObject buildAtlasIndexJson(List<Identifier> items, int cellSize, Identifier texture) {
		int cols = (int) Math.ceil(Math.sqrt(items.size()));
		int rows = (int) Math.ceil((double) items.size() / cols);

		int count = items.size();

		JsonObject atlas = new JsonObject();
		atlas.addProperty("file", texture.getPath() + ".png");
		atlas.addProperty("rows", rows);
		JsonArray size = new JsonArray();
		size.add(cellSize);
		size.add(cellSize);
		atlas.add("texture_size", size);

		JsonArray names = new JsonArray(items.size());

		for (Identifier id : items) {
			names.add(id.toString());
		}

		atlas.add("names", names);

		return atlas;
	}

	public static void writeToFile(JsonObject json, Path path) throws IOException {
		Files.createDirectories(path.getParent());

		try (Writer writer = Files.newBufferedWriter(path)) {
			Gson gson = new GsonBuilder().create();
			gson.toJson(json, writer);
		}
	}

}
