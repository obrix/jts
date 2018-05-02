

/*
 * Copyright (c) 2016 Vivid Solutions.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */
package org.locationtech.jts.operation.overlay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.locationtech.jts.algorithm.BoundaryNodeRule;
import org.locationtech.jts.algorithm.LineIntersector;
import org.locationtech.jts.algorithm.PointLocator;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.TopologyException;
import org.locationtech.jts.geomgraph.Depth;
import org.locationtech.jts.geomgraph.DirectedEdge;
import org.locationtech.jts.geomgraph.DirectedEdgeStar;
import org.locationtech.jts.geomgraph.Edge;
import org.locationtech.jts.geomgraph.EdgeList;
import org.locationtech.jts.geomgraph.EdgeNodingValidator;
import org.locationtech.jts.geomgraph.GeometryGraph;
import org.locationtech.jts.geomgraph.GeometrySRNoder;
import org.locationtech.jts.geomgraph.Label;
import org.locationtech.jts.geomgraph.Node;
import org.locationtech.jts.geomgraph.PlanarGraph;
import org.locationtech.jts.geomgraph.Position;
import org.locationtech.jts.noding.NodedSegmentString;
import org.locationtech.jts.noding.SegmentString;
import org.locationtech.jts.util.Assert;
import org.locationtech.jts.util.Debug;

/**
 * Computes the geometric overlay of two {@link Geometry}s.  The overlay
 * can be used to determine any boolean combination of the geometries.
 *
 * @version 1.7
 */
public class OverlayOp
{
/**
 * The spatial functions supported by this class.
 * These operations implement various boolean combinations of the resultants of the overlay.
 */
	
  /**
   * The code for the Intersection overlay operation.
   */
  public static final int INTERSECTION  = 1;
  
  /**
   * The code for the Union overlay operation.
   */
  public static final int UNION         = 2;
  
  /**
   *  The code for the Difference overlay operation.
   */
  public static final int DIFFERENCE    = 3;
  
  /**
   *  The code for the Symmetric Difference overlay operation.
   */
  public static final int SYMDIFFERENCE = 4;

  /**
   * Computes an overlay operation for 
   * the given geometry arguments.
   * 
   * @param geom0 the first geometry argument
   * @param geom1 the second geometry argument
   * @param opCode the code for the desired overlay operation
   * @return the result of the overlay operation
   * @throws TopologyException if a robustness problem is encountered
   */
  public static Geometry overlayOp(Geometry geom0, Geometry geom1, int opCode)
  {
    OverlayOp gov = new OverlayOp(geom0, geom1);
    Geometry geomOv = gov.getResultGeometry(opCode);
    return geomOv;
  }
  
  public static Geometry overlayOp(Geometry geom0, Geometry geom1, int opCode, PrecisionModel nodingPM)
  {
    OverlayOp ov = new OverlayOp(geom0, geom1);
    ov.setNodingPrecision(nodingPM);
    Geometry geomOv = ov.getResultGeometry(opCode);
    return geomOv;
  }

  /**
   * Tests whether a point with a given topological {@link Label}
   * relative to two geometries is contained in 
   * the result of overlaying the geometries using
   * a given overlay operation.
   * <p>
   * The method handles arguments of {@link Location#NONE} correctly
   * 
   * @param label the topological label of the point
   * @param opCode the code for the overlay operation to test
   * @return true if the label locations correspond to the overlayOpCode
   */
  public static boolean isResultOfOp(Label label, int opCode)
  {
    int loc0 = label.getLocation(0);
    int loc1 = label.getLocation(1);
    return isResultOfOp(loc0, loc1, opCode);
  }

  /**
   * Tests whether a point with given {@link Location}s
   * relative to two geometries is contained in 
   * the result of overlaying the geometries using
   * a given overlay operation.
   * <p>
   * The method handles arguments of {@link Location#NONE} correctly
   *
   * @param loc0 the code for the location in the first geometry 
   * @param loc1 the code for the location in the second geometry 
   * @param overlayOpCode the code for the overlay operation to test
   * @return true if the locations correspond to the overlayOpCode
   */
  public static boolean isResultOfOp(int loc0, int loc1, int overlayOpCode)
  {
    if (loc0 == Location.BOUNDARY) loc0 = Location.INTERIOR;
    if (loc1 == Location.BOUNDARY) loc1 = Location.INTERIOR;
    switch (overlayOpCode) {
    case INTERSECTION:
      return loc0 == Location.INTERIOR
          && loc1 == Location.INTERIOR;
    case UNION:
      return loc0 == Location.INTERIOR
          || loc1 == Location.INTERIOR;
    case DIFFERENCE:
      return loc0 == Location.INTERIOR
          && loc1 != Location.INTERIOR;
    case SYMDIFFERENCE:
      return   (     loc0 == Location.INTERIOR &&  loc1 != Location.INTERIOR)
            || (     loc0 != Location.INTERIOR &&  loc1 == Location.INTERIOR);
    }
    return false;
  }
  
  private Geometry[] inputGeom;  // the input geometries
  private GeometryFactory geomFact;
  
  private PrecisionModel nodingPrecisionModel;
  private PrecisionModel resultPrecisionModel;
  private Geometry resultGeom;

  private PlanarGraph graph;
  private final PointLocator ptLocator = new PointLocator();

  private List resultPolyList   = new ArrayList();
  private List resultLineList   = new ArrayList();
  private List resultPointList  = new ArrayList();

  private boolean useSnapRounding = false;


  /**
   * Constructs an instance to compute a single overlay operation
   * for the given geometries.
   * 
   * @param g0 the first geometry argument
   * @param g1 the second geometry argument
   */
  public OverlayOp(Geometry g0, Geometry g1) {
    inputGeom = new Geometry[] { g0, g1 };
    resultPrecisionModel = highestPrecision(g0, g1);
    nodingPrecisionModel = resultPrecisionModel;
    
    /**
     * Use factory of primary geometry.
     * Note that this does NOT handle mixed-precision arguments
     * where the second geometry has greater precision than the first.
     */
    geomFact = g0.getFactory();
  }

  public void setNodingPrecision(PrecisionModel pm) {
    nodingPrecisionModel = pm;
    useSnapRounding  = true;
  }
  
  private static PrecisionModel highestPrecision(Geometry g0, Geometry g1)
  {
    PrecisionModel g0pm = g0.getPrecisionModel();
    PrecisionModel g1pm = g1.getPrecisionModel();
    if (g0pm.compareTo(g1pm) >= 0) 
      return g0pm;
    return g1pm;
  }
  
  public Geometry getArgGeometry(int i) { return inputGeom[i]; }
  
  /**
   * Gets the result of the overlay for a given overlay operation.
   * <p>
   * Note: this method can be called once only.
   * 
   * @param overlayOpCode the overlay operation to perform
   * @return the compute result geometry
   * @throws TopologyException if a robustness problem is encountered
   */
  public Geometry getResultGeometry(int overlayOpCode)
  {
    computeOverlay(overlayOpCode);
    return resultGeom;
  }

  /**
   * Gets the graph constructed to compute the overlay.
   * 
   * @return the overlay graph
   */
  public PlanarGraph getGraph() { return graph; }

  private void computeOverlay(int opCode)
  {
    graph = new PlanarGraph(new OverlayNodeFactory());
    // Noding may be done under different precision model
    
    EdgeList edgeList = computeNoding();

    // add the noded edges to the result graph
    graph.addEdges(edgeList.getEdges());
    computeLabelling();
//Debug.printWatch();
    labelIncompleteNodes();
//Debug.printWatch();
//nodeMap.print(System.out);

    buildOutput(graph, opCode);

    // gather the results from all calculations into a single Geometry for the result set
    resultGeom = computeGeometry(resultPointList, resultLineList, resultPolyList, opCode);
  }

  private EdgeList computeNoding() {
    List nodedInputEdges;
    if (useSnapRounding) {
      nodedInputEdges = nodeEdgesSR(inputGeom, graph, nodingPrecisionModel);
    }
    else {
      nodedInputEdges = nodeEdges(inputGeom, graph, nodingPrecisionModel);  
    }
    
    EdgeList edgeList = mergeDuplicateEdges(nodedInputEdges);
    computeLabelsFromDepths(edgeList);
    replaceCollapsedEdges(edgeList);

Debug.println(edgeList);

    /**
     * Check that the noding completed correctly.
     * 
     * This test is slow, but necessary in order to catch robustness failure 
     * situations.
     * If an exception is thrown because of a noding failure, 
     * then snapping can be performed, which will hopefully avoid the problem.
     * In the future hopefully a faster check can be developed.  
     * 
     */
// TODO: use only for classic noding.  Not needed for SR
    EdgeNodingValidator.checkValid(edgeList.getEdges());
    return edgeList;
  }


  private List nodeEdges(Geometry[] geom, PlanarGraph graph, PrecisionModel precisionModel) {
    GeometryGraph geomGraph0 = new GeometryGraph(0, geom[0]);
    GeometryGraph geomGraph1 = new GeometryGraph(1, geom[1]);
    
    // copy points from input Geometries.
    // This ensures that any Point geometries
    // in the input are considered for inclusion in the result set
    copyPoints(0, geomGraph0, graph);
    copyPoints(1, geomGraph1, graph);

    LineIntersector li = new RobustLineIntersector();
    li.setPrecisionModel(precisionModel);
    
    // node the input Geometries
    geomGraph0.computeSelfNodes(li, false);
    geomGraph1.computeSelfNodes(li, false);

    // compute intersections between edges of the two input geometries
    geomGraph0.computeEdgeIntersections(geomGraph1, li, true);

    List baseSplitEdges = new ArrayList();
    geomGraph0.computeSplitEdges(baseSplitEdges);
    geomGraph1.computeSplitEdges(baseSplitEdges);
    return baseSplitEdges;
  }
  
  private List nodeEdgesSR(Geometry[] geom, PlanarGraph graph, PrecisionModel precisionModel) {
    GeometrySRNoder noder = new GeometrySRNoder(precisionModel);
    noder.add(geom[0], 0);
    noder.add(geom[1], 1);
    Collection nodedSegStrings = noder.node();
    List edges = toEdgesFromSegmentStrings(nodedSegStrings);
    return edges;
  }
  
  private static List toEdgesFromSegmentStrings(Collection segStrings) {
    List edges = new ArrayList();
    for (Iterator i = segStrings.iterator(); i.hasNext(); ) {
      SegmentString ss = (SegmentString) i.next();
      Coordinate[] coords = ss.getCoordinates();
      Label lbl = (Label) ss.getData();
      // replicate label, to avoid aliasing on split edges (which have the same label object)
      Edge edge = new Edge(coords, new Label(lbl));
      edges.add(edge);
    }
    return edges;
  }

  /**
   * Copy all nodes from an arg geometry into this graph.
   * The node label in the arg geometry overrides any previously computed
   * label for that argIndex.
   * (E.g. a node may be an intersection node with
   * a previously computed label of BOUNDARY,
   * but in the original arg Geometry it is actually
   * in the interior due to the Boundary Determination Rule)
   * @param geomGraph 
   * @param graph2 
   */
  private void copyPoints(int argIndex, GeometryGraph geomGraph, PlanarGraph graph)
  {
    for (Iterator i = geomGraph.getNodeIterator(); i.hasNext(); ) {
      Node graphNode = (Node) i.next();
      Node newNode = graph.addNode(graphNode.getCoordinate());
      newNode.setLabel(argIndex, graphNode.getLabel().getLocation(argIndex));
    }
  }
  
  private void buildOutput(PlanarGraph graph, int opCode) {
    /**
     * The ordering of building the result Geometries is important.
     * Areas must be built before lines, which must be built before points.
     * This is so that lines which are covered by areas are not included
     * explicitly, and similarly for points.
     */
    findResultAreaEdges(opCode);
    cancelDuplicateResultEdges();

    PolygonBuilder polyBuilder = new PolygonBuilder(geomFact);
    polyBuilder.add(graph);
    resultPolyList = polyBuilder.getPolygons();

    LineBuilder lineBuilder = new LineBuilder(this, geomFact, ptLocator);
    resultLineList = lineBuilder.build(opCode);

    PointBuilder pointBuilder = new PointBuilder(this, geomFact, ptLocator);
    resultPointList = pointBuilder.build(opCode);
  }
  
  private EdgeList mergeDuplicateEdges(List edges)
  {
    EdgeList edgeList = new EdgeList();
    for (Iterator i = edges.iterator(); i.hasNext(); ) {
      Edge e = (Edge) i.next();
      mergeDuplicateEdge(e, edgeList);
    }
    return edgeList;
  }
  /**
   * Insert an edge from one of the noded input graphs.
   * Checks edges that are inserted to see if an
   * identical edge already exists.
   * If so, the edge is not inserted, but its label is merged
   * with the existing edge.
   * @param edgeList 
   */
  private void mergeDuplicateEdge(Edge e, EdgeList edgeList)
  {
//<FIX> MD 8 Oct 03  speed up identical edge lookup
    // fast lookup
    Edge existingEdge = edgeList.findEqualEdge(e);

    // If an identical edge already exists, simply update its label
    if (existingEdge != null) {
      Label existingLabel = existingEdge.getLabel();

      Label labelToMerge = e.getLabel();
      // check if new edge is in reverse direction to existing edge
      // if so, must flip the label before merging it
      if (! existingEdge.isPointwiseEqual(e)) {
        labelToMerge = new Label(e.getLabel());
        labelToMerge.flip();
      }
      Depth depth = existingEdge.getDepth();
      // if this is the first duplicate found for this edge, initialize the depths
      ///*
      if (depth.isNull()) {
        depth.add(existingLabel);
      }
      //*/
      depth.add(labelToMerge);
      existingLabel.merge(labelToMerge);
//Debug.print("inserted edge: "); Debug.println(e);
//Debug.print("existing edge: "); Debug.println(existingEdge);

    }
    else {  // no matching existing edge was found
      // add this new edge to the list of edges in this graph
      //e.setName(name + edges.size());
      //e.getDepth().add(e.getLabel());
      edgeList.add(e);
    }
  }

  /**
   * If either of the GeometryLocations for the existing label is
   * exactly opposite to the one in the labelToMerge,
   * this indicates a dimensional collapse has happened.
   * In this case, convert the label for that Geometry to a Line label
   */
   /* NOT NEEDED?
  private void checkDimensionalCollapse(Label labelToMerge, Label existingLabel)
  {
    if (existingLabel.isArea() && labelToMerge.isArea()) {
      for (int i = 0; i < 2; i++) {
        if (! labelToMerge.isNull(i)
            &&  labelToMerge.getLocation(i, Position.LEFT)  == existingLabel.getLocation(i, Position.RIGHT)
            &&  labelToMerge.getLocation(i, Position.RIGHT) == existingLabel.getLocation(i, Position.LEFT) )
        {
          existingLabel.toLine(i);
        }
      }
    }
  }
  */
  /**
   * Update the labels for edges according to their depths.
   * For each edge, the depths are first normalized.
   * Then, if the depths for the edge are equal,
   * this edge must have collapsed into a line edge.
   * If the depths are not equal, update the label
   * with the locations corresponding to the depths
   * (i.e. a depth of 0 corresponds to a Location of EXTERIOR,
   * a depth of 1 corresponds to INTERIOR)
   */
  private void computeLabelsFromDepths(EdgeList edgeList)
  {
    for (Iterator it = edgeList.iterator(); it.hasNext(); ) {
      Edge e = (Edge) it.next();
      Label lbl = e.getLabel();
      Depth depth = e.getDepth();
      /**
       * Only check edges for which there were duplicates,
       * since these are the only ones which might
       * be the result of dimensional collapses.
       */
      if (! depth.isNull()) {
        depth.normalize();
        for (int i = 0; i < 2; i++) {
          if (! lbl.isNull(i) && lbl.isArea() && ! depth.isNull(i)) {
          /**
           * if the depths are equal, this edge is the result of
           * the dimensional collapse of two or more edges.
           * It has the same location on both sides of the edge,
           * so it has collapsed to a line.
           */
            if (depth.getDelta(i) == 0) {
              lbl.toLine(i);
            }
            else {
            /**
             * This edge may be the result of a dimensional collapse,
             * but it still has different locations on both sides.  The
             * label of the edge must be updated to reflect the resultant
             * side locations indicated by the depth values.
             */
              Assert.isTrue(! depth.isNull(i, Position.LEFT), "depth of LEFT side has not been initialized");
              lbl.setLocation(i, Position.LEFT,   depth.getLocation(i, Position.LEFT));
              Assert.isTrue(! depth.isNull(i, Position.RIGHT), "depth of RIGHT side has not been initialized");
              lbl.setLocation(i, Position.RIGHT,  depth.getLocation(i, Position.RIGHT));
            }
          }
        }
      }
    }
  }
  /**
   * If edges which have undergone dimensional collapse are found,
   * replace them with a new edge which is a L edge
   */
  private void replaceCollapsedEdges(EdgeList edgeList)
  {
    List newEdges = new ArrayList();
    for (Iterator it = edgeList.iterator(); it.hasNext(); ) {
      Edge e = (Edge) it.next();
      if (e.isCollapsed()) {
//Debug.print(e);
        it.remove();
        newEdges.add(e.getCollapsedEdge());
      }
    }
    edgeList.addAll(newEdges);
  }


  /**
   * Compute initial labelling for all DirectedEdges at each node.
   * In this step, DirectedEdges will acquire a complete labelling
   * (i.e. one with labels for both Geometries)
   * only if they
   * are incident on a node which has edges for both Geometries
   */
  private void computeLabelling()
  {
    for (Iterator nodeit = graph.getNodes().iterator(); nodeit.hasNext(); ) {
      Node node = (Node) nodeit.next();
//if (node.getCoordinate().equals(new Coordinate(222, 100)) ) Debug.addWatch(node.getEdges());
      node.getEdges().computeLabelling(inputGeom, BoundaryNodeRule.OGC_SFS_BOUNDARY_RULE);
    }
    mergeSymLabels();
    updateNodeLabelling();
  }
  /**
   * For nodes which have edges from only one Geometry incident on them,
   * the previous step will have left their dirEdges with no labelling for the other
   * Geometry.  However, the sym dirEdge may have a labelling for the other
   * Geometry, so merge the two labels.
   */
  private void mergeSymLabels()
  {
    for (Iterator nodeit = graph.getNodes().iterator(); nodeit.hasNext(); ) {
      Node node = (Node) nodeit.next();
      ((DirectedEdgeStar) node.getEdges()).mergeSymLabels();
//node.print(System.out);
    }
  }
  private void updateNodeLabelling()
  {
    // update the labels for nodes
    // The label for a node is updated from the edges incident on it
    // (Note that a node may have already been labelled
    // because it is a point in one of the input geometries)
    for (Iterator nodeit = graph.getNodes().iterator(); nodeit.hasNext(); ) {
      Node node = (Node) nodeit.next();
      Label lbl = ((DirectedEdgeStar) node.getEdges()).getLabel();
      node.getLabel().merge(lbl);
    }
  }

  /**
   * Incomplete nodes are nodes whose labels are incomplete.
   * (e.g. the location for one Geometry is null).
   * These are either isolated nodes,
   * or nodes which have edges from only a single Geometry incident on them.
   *
   * Isolated nodes are found because nodes in one graph which don't intersect
   * nodes in the other are not completely labelled by the initial process
   * of adding nodes to the nodeList.
   * To complete the labelling we need to check for nodes that lie in the
   * interior of edges, and in the interior of areas.
   * <p>
   * When each node labelling is completed, the labelling of the incident
   * edges is updated, to complete their labelling as well.
   */
  private void labelIncompleteNodes()
  {
  	int nodeCount = 0;
    for (Iterator ni = graph.getNodes().iterator(); ni.hasNext(); ) {
      Node n = (Node) ni.next();
      Label label = n.getLabel();
      if (n.isIsolated()) {
      	nodeCount++;
        if (label.isNull(0))
          labelIncompleteNode(n, 0);
        else
          labelIncompleteNode(n, 1);
      }
      // now update the labelling for the DirectedEdges incident on this node
      ((DirectedEdgeStar) n.getEdges()).updateLabelling(label);
//n.print(System.out);
    }
    /*
    int nPoly0 = arg[0].getGeometry().getNumGeometries();
    int nPoly1 = arg[1].getGeometry().getNumGeometries();
    System.out.println("# isolated nodes= " + nodeCount 
    		+ "   # poly[0] = " + nPoly0
    		+ "   # poly[1] = " + nPoly1);
    */
  }

  /**
   * Label an isolated node with its relationship to the target geometry.
   */
  private void labelIncompleteNode(Node n, int targetIndex)
  {
    int loc = ptLocator.locate(n.getCoordinate(), inputGeom[targetIndex]);
  	
  	// MD - 2008-10-24 - experimental for now
//    int loc = arg[targetIndex].locate(n.getCoordinate());
    n.getLabel().setLocation(targetIndex, loc);
  }

  /**
   * Find all edges whose label indicates that they are in the result area(s),
   * according to the operation being performed.  Since we want polygon shells to be
   * oriented CW, choose dirEdges with the interior of the result on the RHS.
   * Mark them as being in the result.
   * Interior Area edges are the result of dimensional collapses.
   * They do not form part of the result area boundary.
   */
  private void findResultAreaEdges(int opCode)
  {
    for (Iterator it = graph.getEdgeEnds().iterator(); it.hasNext(); ) {
      DirectedEdge de = (DirectedEdge) it.next();
    // mark all dirEdges with the appropriate label
      Label label = de.getLabel();
      if (label.isArea()
          && ! de.isInteriorAreaEdge()
          && isResultOfOp(
                label.getLocation(0, Position.RIGHT),
                label.getLocation(1, Position.RIGHT),
                opCode)) {
        de.setInResult(true);
//Debug.print("in result "); Debug.println(de);
      }
    }
  }
  /**
   * If both a dirEdge and its sym are marked as being in the result, cancel
   * them out.
   */
  private void cancelDuplicateResultEdges()
  {
    // remove any dirEdges whose sym is also included
    // (they "cancel each other out")
    for (Iterator it = graph.getEdgeEnds().iterator(); it.hasNext(); ) {
      DirectedEdge de = (DirectedEdge) it.next();
      DirectedEdge sym = de.getSym();
      if (de.isInResult() && sym.isInResult()) {
        de.setInResult(false);
        sym.setInResult(false);
//Debug.print("cancelled "); Debug.println(de); Debug.println(sym);
      }
    }
  }
  /**
   * Tests if a point node should be included in the result or not.
   *
   * @param coord the point coordinate
   * @return true if the coordinate point is covered by a result Line or Area geometry
   */
  public boolean isCoveredByLA(Coordinate coord)
  {
    if (isCovered(coord, resultLineList)) return true;
    if (isCovered(coord, resultPolyList)) return true;
    return false;
  }
  /**
   * Tests if an L edge should be included in the result or not.
   *
   * @param coord the point coordinate
   * @return true if the coordinate point is covered by a result Area geometry
   */
  public boolean isCoveredByA(Coordinate coord)
  {
    if (isCovered(coord, resultPolyList)) return true;
    return false;
  }
  /**
   * @return true if the coord is located in the interior or boundary of
   * a geometry in the list.
   */
  private boolean isCovered(Coordinate coord, List geomList)
  {
    for (Iterator it = geomList.iterator(); it.hasNext(); ) {
      Geometry geom = (Geometry) it.next();
      int loc = ptLocator.locate(coord, geom);
      if (loc != Location.EXTERIOR) return true;
    }
    return false;
  }

  private Geometry computeGeometry( List resultPointList,
                                        List resultLineList,
                                        List resultPolyList,
                                        int opcode)
  {
    List geomList = new ArrayList();
    // element geometries of the result are always in the order P,L,A
    geomList.addAll(resultPointList);
    geomList.addAll(resultLineList);
    geomList.addAll(resultPolyList);
    
    //*
    if (geomList.isEmpty())
    	return createEmptyResult(opcode, inputGeom[0], inputGeom[1], geomFact);
    //*/
    
    // build the most specific geometry possible
    return geomFact.buildGeometry(geomList);
  }

  /**
   * Creates an empty result geometry of the appropriate dimension,
   * based on the given overlay operation and the dimensions of the inputs.
   * The created geometry is always an atomic geometry, 
   * not a collection.
   * <p>
   * The empty result is constructed using the following rules:
   * <ul>
   * <li>{@link #INTERSECTION} - result has the dimension of the lowest input dimension
   * <li>{@link #UNION} - result has the dimension of the highest input dimension
   * <li>{@link #DIFFERENCE} - result has the dimension of the left-hand input
   * <li>{@link #SYMDIFFERENCE} - result has the dimension of the highest input dimension
   * (since the symmetric Difference is the union of the differences).
   * </ul>
   * 
   * @param overlayOpCode the code for the overlay operation being performed
   * @param a an input geometry
   * @param b an input geometry
   * @param geomFact the geometry factory being used for the operation
   * @return an empty atomic geometry of the appropriate dimension
   */
  public static Geometry createEmptyResult(int overlayOpCode, Geometry a, Geometry b, GeometryFactory geomFact)
  {
  	Geometry result = null;
  	switch (resultDimension(overlayOpCode, a, b)) {
  	case -1:
  		result = geomFact.createGeometryCollection();
  		break;
  	case 0:
  		result =  geomFact.createPoint();
  		break;
  	case 1:
  		result =  geomFact.createLineString();
  		break;
  	case 2:
  		result =  geomFact.createPolygon();
  		break;
  	}
		return result;
  }
  
  private static int resultDimension(int opCode, Geometry g0, Geometry g1)
  {
  	int dim0 = g0.getDimension();
  	int dim1 = g1.getDimension();
  	
  	int resultDimension = -1;
  	switch (opCode) {
  	case INTERSECTION: 
  		resultDimension = Math.min(dim0, dim1);
  		break;
  	case UNION: 
  		resultDimension = Math.max(dim0, dim1);
  		break;
  	case DIFFERENCE: 
  		resultDimension = dim0;
  		break;
  	case SYMDIFFERENCE: 
  	  /**
  	   * This result is chosen because
  	   * <pre>
  	   * SymDiff = Union(Diff(A, B), Diff(B, A)
  	   * </pre>
  	   * and Union has the dimension of the highest-dimension argument.
  	   */
  		resultDimension = Math.max(dim0, dim1);
  		break;
  	}
  	return resultDimension;
  }
}
