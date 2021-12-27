/* A representation of a range of runtime instances for a NamedInstance. */

/*
Copyright (c) 2019-2021, The University of California at Berkeley.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package org.lflang.generator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.lflang.lf.Connection;

/**
 * Class representing a range of runtime instance objects
 * (port channels, reactors, reactions, etc.). This class and its derived classes
 * have the most detailed information about the structure of a Lingua Franca
 * program.  There are three levels of detail:
 * 
 * * The abstract syntax tree (AST).
 * * The compile-time instance graph (CIG).
 * * The runtime instance graph (RIG).
 * 
 * In the AST, each reactor class is represented once.
 * In the CIG, each reactor class is represented as many times as it is
 * instantiated, except that a bank has only one representation (as
 * in the graphical rendition). Equivalently, each CIG node has a unique
 * full name, even though it may represent many runtime instances.
 * The CIG is represented by
 * {@link NamedInstance<T extends EObject>} and its derived classes.
 * In the RIG, each bank is expanded so each bank member and
 * each port channel is represented.
 * 
 * In general, determining dependencies between reactions requires analysis
 * at the level of the RIG.  But a brute-force representation of the RIG
 * can get very large, and for most programs it has a great deal of
 * redundancy. In a fully detailed representation of the RIG, for example,
 * a bank of width N that contains a bank of width M within which there
 * is a reactor R with port P will have N*M runtime instances of the port.
 * If the port is a multiport with width L, then there are N*M*L
 * edges connected to instances of that port, each of which may go
 * to a distinct set of other remote runtime port instances.
 * 
 * This class and its subclasses give a more compact representation of the
 * RIG in most common cases where collections of runtime instances all have
 * the same dependencies.
 * 
 * A Range represents an adjacent set of RIG objects (port channels, reactions
 * reactors). For example, it can represent port channels 2 through 5 in a multiport
 * of width 10.  The width in this case is 4. If such a port is
 * contained by one or more banks of reactors, then channels 2 through 5
 * of one bank member form a contiguous range. If you want channels 2 through 5
 * of all bank members, then this needs to be represented with multiple ranges.
 * 
 * The maxWidth is the width of the instance multiplied by the widths of
 * each of its containers. For example, if a port of width 4 is contained by
 * a bank of width 2 that is then contained by a bank of width 3, then
 * the maxWidth will be 2*3*4 = 24.
 * 
 * The function iterationOrder returns a list that includes the instance
 * of this range and all its containers, except the top-level reactor (main
 * or federated).  The order of this list is the order in which an
 * iteration over the RIG objects represented by this range should be
 * iterated. If the instance is a PortInstance, then this order will
 * depend on whether connections at any level of the hierarchy are
 * interleaved.
 * 
 * The simplest Ranges are those where the corresponding CIG node represents
 * only one runtime instance (its instance is not (deeply) within a bank 
 * and is not a multiport). In this case, the Range and all the objects
 * returned by iterationOrder will have width 1.
 * 
 * In a more complex instance, consider a bank A of width 2 that contains a
 * bank B of width 2 that contains a port instance P with width 2. .
 * There are a total of 8 instances of P, which we can name:
 * 
 *      A0.B0.P0
 *      A0.B0.P1
 *      A0.B1.P0
 *      A0.B1.P1
 *      A1.B0.P0
 *      A1.B0.P1
 *      A1.B1.P0
 *      A1.B1.P1
 * 
 * If there is no interleaving, iterationOrder() returns [P, B, A],
 * indicating that they should be iterated by incrementing the index of P
 * first, then the index of B, then the index of A, as done above.
 * 
 * If the connection within B to port P is interleaved, then the order
 * of iteration order will be [B, P, A], resulting in the list:
 * 
 *      A0.B0.P0
 *      A0.B1.P0
 *      A0.B0.P1
 *      A0.B1.P1
 *      A1.B0.P0
 *      A1.B1.P0
 *      A1.B0.P1
 *      A1.B1.P1
 *      
 *  If the connection within A to B is also interleaved, then the order
 *  will be [A, B, P], resulting in the list:
 *  
 *      A0.B0.P0
 *      A1.B0.P0
 *      A0.B1.P0
 *      A1.B1.P0
 *      A0.B0.P1
 *      A1.B0.P1
 *      A0.B1.P1
 *      A1.B1.P1
 * 
 * Finally, if the connection within A to B is interleaved, but not the
 * connection within B to P, then the order will be [A, P, B], resulting in
 * the list:
 * 
 *      A0.B0.P0
 *      A1.B0.P0
 *      A0.B0.P1
 *      A1.B0.P1
 *      A0.B1.P0
 *      A1.B1.P0
 *      A0.B1.P1
 *      A1.B1.P1
 * 
 * A Range is a contiguous subset of one of the above lists, given by
 * a start offset and a width that is less than or equal to maxWidth.
 * 
 * For a Range with width greater than 1,
 * the head() and tail() functions split the range.
 * 
 * This class and subclasses are designed to be immutable.
 * Modifications always return a new Range.
 *
 * @author{Edward A. Lee <eal@berkeley.edu>}
 */
public class RuntimeRange<T extends NamedInstance<?>> implements Comparable<RuntimeRange<?>> {
    
    /**
     * Create a new range representing the full width of the specified instance
     * with no interleaving. The instances will be a list with the specified instance
     * first, its parent next, and on up the hierarchy until the depth 1 parent (the
     * top-level reactor is not included because it can never be a bank).
     * @param instance The instance.
     * @param connection The connection establishing this range or null if not unique.
     */
    public RuntimeRange(
            T instance,
            Connection connection
    ) {
        this(instance, 0, 0, connection);
    }

    /**
     * Create a new range representing a range of the specified instance
     * with no interleaving. The instances will be a list with the specified instance
     * first, its parent next, and on up the hierarchy until the depth 1 parent (the
     * top-level reactor is not included because it can never be a bank).
     * @param instance The instance over which this is a range (port, reaction, etc.)
     * @param start The starting index for the range.
     * @param width The width of the range or 0 to specify the maximum possible width.
     * @param connection The connection establishing this range or null if not unique.
     */
    public RuntimeRange(
            T instance,
            int start,
            int width,
            Connection connection
    ) {
        this.instance = instance;
        this.start = start;
        this.connection = connection;
        
        int maxWidth = instance.width; // Initial value.
        NamedInstance<?> parent = instance.parent;
        while (parent.depth > 0) {
            maxWidth *= parent.width;
            parent = parent.parent;
        }
        this.maxWidth = maxWidth;

        if (width > 0 && width + start < maxWidth) {
            this.width = width;
        } else {
            this.width = maxWidth - start;
        }
    }
    
    //////////////////////////////////////////////////////////
    //// Public variables
    
    /** The connection establishing this range or null if not unique. */
    public final Connection connection;

    /** The instance that this is a range of. */
    public final T instance;
    
    /** The start offset of this range. */
    public final int start;
    
    /** The maximum width of any range with this instance. */
    public final int maxWidth;
    
    /** The width of this range. */
    public final int width;
    
    //////////////////////////////////////////////////////////
    //// Public methods

    /**
     * Compare ranges by first comparing their start offset, and then,
     * if these are equal, comparing their widths. This comparison is
     * meaningful only for ranges that have the same instances.
     * Note that this can return 0 even if equals() does not return true.
     */
    @Override
    public int compareTo(RuntimeRange<?> o) {
        if (start < o.start) {
            return -1;
        } else if (start == o.start) {
            if (width < o.width) {
                return -1;
            } else if (width == o.width) {
                return 0;
            } else {
                return 1;
            }
        } else {
            return 1;
        }
    }
    
    /**
     * Return a new Range that is identical to this range but
     * with width reduced to the specified width.
     * If the new width is greater than or equal to the width
     * of this range, then return this range.
     * If the newWidth is 0 or negative, return null.
     * @param newWidth The new width.
     */
    public RuntimeRange<T> head(int newWidth) {
        if (newWidth >= width) return this;
        if (newWidth <= 0) return null;
        return new RuntimeRange<T>(instance, start, newWidth, connection);
    }
    
    /**
     * Return the list of **natural identifiers** for the runtime instances
     * in this range.  Each returned identifier is an integer representation
     * of the mixed-radix number [d0, ... , dn] with radices [w0, ... , wn],
     * where d0 is the bank or channel index of this Range's instance, which
     * has width w0, and dn is the bank index of its topmost parent below the 
     * top-level (main) reactor, which has width wn. The depth of this Range's 
     * instance, therefore, is n - 1.  The order of the returned list is the order
     * in which the runtime instances should be iterated.
     */
    public List<Integer> instances() {
        List<Integer> result = new ArrayList<Integer>(width);
        List<MixedRadixInt> mr = instancesMR();
        for (MixedRadixInt i : mr) {
            result.add(i.get());
        }
        return result;
    }
    
    /**
     * Return a list containing the instance for this range and all of its
     * parents, not including the top level reactor, in the order in which
     * their banks and multiport channels should be iterated.
     * For each depth at which the connection is interleaved, that parent
     * will appear before this instance in depth order (shallower to deeper).
     * For each depth at which the connection is not interleaved, that parent
     * will appear after this instance in reverse depth order (deeper to
     * shallower).
     */
    public List<NamedInstance<?>> iterationOrder() {
        ArrayList<NamedInstance<?>> result = new ArrayList<NamedInstance<?>>();
        result.add(instance);
        ReactorInstance parent = instance.parent;
        while (parent.depth > 0) {
            if (_interleaved.contains(parent)) {
                // Put the parent at the head of the list.
                result.add(0, parent);
            } else {
                result.add(parent);
            }
            parent = parent.parent;
        }
        return result;
    }
    
    /**
     * Return a set of identifiers for runtime instances of a parent of this
     * Range's instance n levels above this Range's instance. If n == 1, for
     * example, then this return the identifiers for the parent ReactorInstance.
     * 
     * This returns a list of **natural identifiers**,
     * as defined below, for the instances within the range.
     * 
     * The resulting list can be used to count the number of distinct
     * runtime instances of this Range's instance (using n == 0) or any of its parents that
     * lie within the range and to provide an index into an array of runtime
     * instances.
     * 
     * Each **natural identifier** is the integer value of a mixed-radix number
     * defined as follows:
     * 
     * * The low-order digit is the index of the runtime instance of i
     *   within its container. If the NamedInstance
     *   is a PortInstance, this will be the multiport channel or 0 if it is not a
     *   multiport. If the NamedInstance is a ReactorInstance, then it will be the bank
     *   index or 0 if the reactor is not a bank.  The radix for this digit will be
     *   the multiport width or bank width or 1 if the NamedInstance is neither a
     *   multiport nor a bank.
     *   
     * * The next digit will be the bank index of the container of the specified
     *   NamedInstance or 0 if it is not a bank.
     *   
     * * The remaining digits will be bank indices of containers up to but not
     *   including the top-level reactor (there is no point in including the top-level
     *   reactor because it is never a bank.
     *   
     * Each index that is returned can be used as an index into an array of
     * runtime instances that is assumed to be in a **natural order**.
     * 
     * @param n The number of levels up of the parent. This is required to be
     *  less than the depth of this Range's instance or an exception will be thrown.
     */
    public Set<Integer> parentInstances(int n) {
        Set<Integer> result = new LinkedHashSet<Integer>(width);        
        List<MixedRadixInt> mr = instancesMR();
        for (MixedRadixInt m : mr) {
            result.add(m.drop(n).get());
        }
        return result;
    }

    /**
     * Return the nearest containing ReactorInstance for this instance.
     * If this instance is a ReactorInstance, then return it.
     * Otherwise, return its parent.
     */
    public ReactorInstance parentReactor() {
        if (instance instanceof ReactorInstance) {
            return (ReactorInstance)instance;
        } else {
            return instance.getParent();
        }
    }
    
    /**
     * Return that permutation that will convert the mixed-radix number
     * with digits given by {@link #startIndices()} into a mixed-radix
     * number in natural order, i.e. with digits given by
     * {@link #startIndicesNatural()}.
     */
    public List<Integer> permutation() {
        List<Integer> result = new ArrayList<Integer>(instance.depth);
        // Populate the result with default zeros.
        for (int i = 0; i < instance.depth; i++) {
            result.add(0);
        }
        int count = 0;
        for (NamedInstance<?> i : iterationOrder()) {
            result.set(instance.depth - i.depth, count++);
        }
        return result;
    }
    
    /**
     * Return the start as a mixed-radix number where the digits and
     * radixes are in the order given by {@link #instances()}.
     * For any instance that is neither a
     * multiport nor a bank, the digit will be 0.
     */
    public MixedRadixInt startMR() {
        List<Integer> digits = new ArrayList<Integer>(instance.depth);
        List<Integer> radixes = new ArrayList<Integer>(instance.depth);
        int factor = 1;
        for (NamedInstance<?> i : iterationOrder()) {
            int iStart = (start / factor) % i.width;
            factor *= i.width;
            digits.add(iStart);
            radixes.add(i.width);
        }
        return new MixedRadixInt(digits, radixes);
    }

    /**
     * Return the start as a mixed-radix number where the digits and 
     * radixes are in hierarchy order (natural order), with this instance's
     * start position listed first. For any instance that is neither a
     * multiport nor a bank, the digit will be 0.
     */
    public MixedRadixInt startMRNatural() {
        return startMR().permute(permutation());
    }

    /**
     * Return a new range that represents the leftover elements
     * starting at the specified offset relative to start.
     * If start + offset is greater than or equal to the width, then this returns null.
     * If this offset is 0 then this returns this range unmodified.
     * @param offset The number of elements to consume. 
     */
    public RuntimeRange<T> tail(int offset) {
        if (offset == 0) return this;
        if (offset >= width) return null;
        return new RuntimeRange<T>(instance, start + offset, width - offset, connection);
    }
    
    /**
     * Toggle the interleaved status of the specified reactor, which is assumed
     * to be a parent of the instance of this range.
     * If it was previously interleaved, make it not interleaved
     * and vice versa.  This returns a new Range.
     * @param reactor The parent reactor at which to toggle interleaving.
     */
    public RuntimeRange<T> toggleInterleaved(ReactorInstance reactor) {
        Set<ReactorInstance> newInterleaved = new HashSet<ReactorInstance>(_interleaved);
        if (_interleaved.contains(reactor)) {
            newInterleaved.remove(reactor);
        } else {
            newInterleaved.add(reactor);
        }
        RuntimeRange<T> result = new RuntimeRange<T>(instance, start, width, connection);
        result._interleaved = newInterleaved;
        return result;
    }
        
    @Override
    public String toString() {
        return instance.getFullName() + "(" + start + "," + width + ")";
    }
    
    //////////////////////////////////////////////////////////
    //// Protected variables
    
    /** Record of which levels are interleaved. */
    Set<ReactorInstance> _interleaved = new HashSet<ReactorInstance>();
    
    //////////////////////////////////////////////////////////
    //// Protected methods

    /**
     * Return the list of MixedRadixInt identifiers for the runtime instances
     * in this range.  Each returned identifier is a mixed-radix number
     * [d0, ... , dn] with radixes [w0, ... , wn], where d0 is the bank or
     * channel index of this Range's instance, which has width w0, and
     * dn is the bank index of its topmost parent below the top-level (main)
     * reactor, which has width wn. The depth of this Range's instance,
     * therefore, is n - 1.  The order of the returned list is the order
     * in which the runtime instances should be iterated.
     */
    protected List<MixedRadixInt> instancesMR() {
        List<MixedRadixInt> result = new ArrayList<MixedRadixInt>(width);
        
        // First, build mixed-radix number with value 0.
        List<Integer> radixes = new ArrayList<Integer>(instance.depth);
        List<Integer> digits = new ArrayList<Integer>(instance.depth);
        List<Integer> permutation = new ArrayList<Integer>(instance.depth);
        
        // Pre-fill the permutation array with the natural order.
        for (int j = 0; j < instance.depth; j++) permutation.add(j);
                
        // Construct the radix and permutation arrays.
        int count = 0;
        for (NamedInstance<?> io : iterationOrder()) {
            radixes.add(io.width);
            permutation.set(instance.depth - io.depth, count);
            digits.add(0);
            count++;
        }
                
        // Iterate over the range in its own order.
        count = 0;
        MixedRadixInt indices = new MixedRadixInt(digits, radixes);
        while (count < start + width) {
            if (count >= start) {
                // Found an instance to include in the list.
                // Permute it to get natural order.
                result.add(indices.permute(permutation));
            }
            indices.increment();
            count++;
        }
        return result;
    }

    //////////////////////////////////////////////////////////
    //// Public inner classes
    
    /**
     * Special case of Range for PortInstance.
     */
    public static class Port extends RuntimeRange<PortInstance> {
        public Port(PortInstance instance) {
            super(instance, null);
        }
        public Port(PortInstance instance, Connection connection) {
            super(instance, connection);
        }
        public Port(PortInstance instance, int start, int width, Connection connection) {
            super(instance, start, width, connection);
        }
    }
}