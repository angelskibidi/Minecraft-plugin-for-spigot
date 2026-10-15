# StarterPlugin: Hub + Spleef

A beginner Spigot plugin project with a hub compass menu and a small Spleef minigame.

## What it does

- Sends players to the hub at `0, 130, 0` when they join.
- Builds a small quartz hub platform and a nearby blue queue platform where the blocks are empty.
- Gives players a compass. Right-click it to open the minigame menu.
- Lets players join or leave the Spleef queue from the menu.
- Starts a 30-second queue countdown once at least two players are waiting. If the queue drops below two, the countdown stops.
- Sends queued players to a separate flat arena world, shows `3`, `2`, `1`, and starts Spleef.
- Gives each player a shovel. Players can break the snow floor; falling off eliminates them. The last player standing wins.
- Restores each player's inventory, armor, game mode, health, and hunger after the match.
- Provides `/hub` to return to the hub and leave a queue or match.
- Keeps `/hello` as a simple starter command.

The arena is created in a separate world named `starter_spleef`; the match floor is rebuilt before every round. The hub platforms are only placed into air blocks.

## Build on Windows

Requirements: JDK 26 and the shaded Spigot API jar at `libs\spigot-api.jar`.

1. Make sure `libs\spigot-api.jar` exists. If it is missing, download the Spigot API shaded jar and save it with that exact name.
2. Double-click `build.bat`, or run it from Command Prompt in this project folder.
3. The finished plugin will be `build\StarterPlugin.jar`.

If your JDK is installed in a different folder, edit `JDK_BIN` near the top of `build.bat`.

## Install on your server

1. Stop the server by typing `stop` in its console.
2. Copy `build\StarterPlugin.jar` into the server's `plugins` folder.
3. Start the server again.
4. Join the server, right-click the compass, choose **Join Spleef Queue**, and have at least two players join.

The menu and game are tested in a local multiplayer server. For a public server, review the hub and queue platform coordinates before enabling the plugin in a world that already has builds at those locations.
