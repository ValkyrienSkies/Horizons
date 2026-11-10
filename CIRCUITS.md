# Circuit boxes

Circuit boxes are gameplay blocks (`horizons:circuit_box`) using the supplied pin-board model. They face north, east, south, or west. Four upper and four lower sockets connect the editor to the world's electrical network.

## Editing

- Right-click the box to open its 11 × 11 board. The left list contains circuit component items in your inventory.
- Select an item, move its preview onto the board, and left-click to place. Q/E rotate the selected component; these bindings are configurable in Controls.
- Right-click a component to remove it. Survival placement consumes items and removal refunds them; creative editing does not consume or refund items.
- Enable wire mode and click two grid points or external sockets. Wires cost no items. The color button changes only their appearance. Right-click a wire to remove it.
- Coincident pins and wire endpoints connect electrically. Wire crossings do not. Empty points can be used as junctions. Wires are displayed as arcs.
- Resistors and capacitors have two adjacent pins and no occupied body cell. NMOS transistors and gates have one body cell which cannot overlap another component body, pin, or wire endpoint.
- In the default orientation, NMOS gate/drain/source pins are left/above/below the body. AND/OR inputs are upper-left/lower-left, and their output is right, centered vertically between the inputs.
- Close the menu with its close button or Escape.

## Schematics and persistence

Insert one piece of paper into the right-hand slot to turn it into a schematic of the current design. Insert a schematic to recreate that design. The schematic is reusable and stores component types, positions, rotations, and wires, not stored electrical energy.

Existing installed components count toward the required materials. All missing components are checked before any changes are made. A failed application leaves the circuit and inventory unchanged and displays an error. Surplus components return to your inventory (or drop if it is full). Creative mode skips material requirements.

Circuit layouts and paper-slot contents persist across saves. Breaking the box in survival drops the box with its design and contents, rather than duplicating its components as separate drops. The network archive separately retains the live simulation state across chunk unloads.

Component artwork and the editor use placeholder visuals. Electrical values are development defaults, not yet configurable: 1 kΩ resistors, 1 mF capacitors, and NMOS transistors with a 1 V threshold. Gates use implicit power/reference, a 2.5 V input threshold, and 0/5 V outputs. This is not intended to model a physically complete integrated circuit.
