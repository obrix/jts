/*
 * Copyright (c) 2016 Martin Davis.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */
package org.locationtech.jts.noding.snapround;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.algorithm.Distance;
import org.locationtech.jts.algorithm.LineIntersector;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.noding.IntersectionAdder;
import org.locationtech.jts.noding.NodedSegmentString;
import org.locationtech.jts.noding.SegmentIntersector;
import org.locationtech.jts.noding.SegmentString;

/**
 * Finds <b>interior</b> intersections between line segments in {@link NodedSegmentString}s,
 * and adds them as nodes
 * using {@link NodedSegmentString#addIntersection(LineIntersector, int, int, int)}.
 *
 * @version 1.7
 */
public class SnapIntersectionAdder
    implements SegmentIntersector
{
  /**
   * The division factor used to determine
   * nearness for snapped intersection detection.
   */
  private static final int NEARNESS_FACTOR = 10;
  
  private LineIntersector li;
  private final List intersections;
  private PrecisionModel precModel;
  private double nearnessTol;


  /**
   * Creates an intersector which finds all snapped interior intersections,
   * and adds them as nodes.
   *
   * @param pm the precision mode to use
   */
  public SnapIntersectionAdder(PrecisionModel pm)
  {
    precModel = pm;
    nearnessTol = 1/precModel.getScale() / NEARNESS_FACTOR;
    li = new RobustLineIntersector();
    intersections = new ArrayList();
  }

  public List getIntersections()  {    return intersections;  }

  /**
   * This method is called by clients
   * of the {@link SegmentIntersector} class to process
   * intersections for two segments of the {@link SegmentString}s being intersected.
   * Note that some clients (such as <code>MonotoneChain</code>s) may optimize away
   * this call for segment pairs which they have determined do not intersect
   * (e.g. by an disjoint envelope test).
   */
  public void processIntersections(
      SegmentString e0,  int segIndex0,
      SegmentString e1,  int segIndex1
      )
  {
    // don't bother intersecting a segment with itself
    if (e0 == e1 && segIndex0 == segIndex1) return;

    Coordinate p00 = e0.getCoordinates()[segIndex0];
    Coordinate p01 = e0.getCoordinates()[segIndex0 + 1];
    Coordinate p10 = e1.getCoordinates()[segIndex1];
    Coordinate p11 = e1.getCoordinates()[segIndex1 + 1];

    li.computeIntersection(p00, p01, p10, p11);
//if (li.hasIntersection() && li.isProper()) Debug.println(li);

    if (li.hasIntersection()) {
      if (li.isInteriorIntersection()) {
        for (int intIndex = 0; intIndex < li.getIntersectionNum(); intIndex++) {
          intersections.add(li.getIntersection(intIndex));
        }
        ((NodedSegmentString) e0).addIntersections(li, segIndex0, 0);
        ((NodedSegmentString) e1).addIntersections(li, segIndex1, 1);
      }
    }
    
    /**
     * To avoid certain robustness issues in snap-rounding, 
     * also treat very near vertex-segment situations as intersections
     */
    processNearVertex(p00, e1, segIndex1, p10, p11 );
    processNearVertex(p01, e1, segIndex1, p10, p11 );
    processNearVertex(p10, e0, segIndex0, p00, p01 );
    processNearVertex(p11, e0, segIndex0, p00, p01 );
  }
  
  /**
   * If an endpoint of one segment is near 
   * the <i>interior</i> of the other segment, add it as an intersection.
   * EXCEPT if the endpoint is also close to a segment endpoint
   * (since this can introduce "zigs" in the linework).
   * <p>
   * This resolves situations where
   * a segment A endpoint is extremely close to another segment B,
   * but is not quite crossing.  Due to robustness issues
   * in orientation detection, this can 
   * result in the snapped segment A crossing segment B
   * without a node being introduced.
   * 
   * @param p
   * @param edge
   * @param segIndex
   * @param p0
   * @param p1
   */
  private void processNearVertex(Coordinate p, SegmentString edge, int segIndex, Coordinate p0, Coordinate p1) {
    
    /**
     * Don't add intersection if candidate point is near endpoints of segment.
     * This avoids creating "zig-zag" linework
     * (since the point could actually be outside the segment envelope).
     */
    if (p.distance(p0) < nearnessTol) return;
    if (p.distance(p1) < nearnessTol) return;
    
    double distSeg = Distance.pointToSegment(p, p0, p1);
    if (distSeg < nearnessTol) {
      intersections.add(p);
      ((NodedSegmentString) edge).addIntersection(p, segIndex);
    }
  }

  /**
   * Always process all intersections
   * 
   * @return false always
   */
  public boolean isDone() { return false; }

}