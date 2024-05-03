package de.intranda.goobi;

/***************************************************************
 * Copyright notice
 *
 * (c) 2016 Robert Sehr <robert.sehr@intranda.com>
 *
 * All rights reserved
 *
 * This file is part of the Goobi project. The Goobi project is free software;
 * you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * The GNU General Public License can be found at
 * http://www.gnu.org/copyleft/gpl.html.
 *
 * This script is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * This copyright notice MUST APPEAR in all copies of this file!
 ***************************************************************/

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class PluginInfo {

	public static void main(String[] args) throws FileNotFoundException, IOException {
		System.out.println("+++++++++++++++++++++++++++++++++++++++++++++++++++++\n");
		System.out.println("This is a Goobi plugin from intranda");
		System.out.println("Copyright 2013 by intranda GmbH");
		System.out.println("All rights reserved");
		System.out.println("info@intranda.com");
		System.out.println("\n-----------------------------------------------------\n");
		System.out.println("This plugin file contains the following plugins:");

		String bla = convertStreamToString(PluginInfo.class.getResourceAsStream("plugins.txt"));
		bla = bla.replaceAll(".class", "");
		bla = bla.replaceAll(" ", "\n");
		System.out.println("\n" + bla);

		System.out.println("\n+++++++++++++++++++++++++++++++++++++++++++++++++++++");
	}

	public static String convertStreamToString(InputStream is) throws IOException {
		StringBuilder sb = new StringBuilder(Math.max(16, is.available()));
		char[] tmp = new char[4096];

		try {
			InputStreamReader reader = new InputStreamReader(is);
			for (int cnt; (cnt = reader.read(tmp)) > 0;)
				sb.append(tmp, 0, cnt);
		} finally {
			is.close();
		}
		return sb.toString();
	}

}
