package com.steelaspect.cytrasyncmatica.litematica;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;

public interface MovingFinisher {
    void onFinishedMoving(String subRegionName, SchematicPlacementManager manager);
}
