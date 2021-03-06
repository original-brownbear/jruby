/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby;

import static org.jruby.util.Numeric.f_abs;
import static org.jruby.util.Numeric.f_add;
import static org.jruby.util.Numeric.f_cmp;
import static org.jruby.util.Numeric.f_div;
import static org.jruby.util.Numeric.f_equal;
import static org.jruby.util.Numeric.f_expt;
import static org.jruby.util.Numeric.f_floor;
import static org.jruby.util.Numeric.f_gcd;
import static org.jruby.util.Numeric.f_idiv;
import static org.jruby.util.Numeric.f_inspect;
import static org.jruby.util.Numeric.f_integer_p;
import static org.jruby.util.Numeric.f_minus_one_p;
import static org.jruby.util.Numeric.f_mul;
import static org.jruby.util.Numeric.f_negate;
import static org.jruby.util.Numeric.f_negative_p;
import static org.jruby.util.Numeric.f_odd_p;
import static org.jruby.util.Numeric.f_one_p;
import static org.jruby.util.Numeric.f_rshift;
import static org.jruby.util.Numeric.f_sub;
import static org.jruby.util.Numeric.f_to_f;
import static org.jruby.util.Numeric.f_to_i;
import static org.jruby.util.Numeric.f_to_r;
import static org.jruby.util.Numeric.f_to_s;
import static org.jruby.util.Numeric.f_truncate;
import static org.jruby.util.Numeric.f_xor;
import static org.jruby.util.Numeric.f_zero_p;
import static org.jruby.util.Numeric.i_gcd;
import static org.jruby.util.Numeric.i_ilog2;
import static org.jruby.util.Numeric.k_exact_p;
import static org.jruby.util.Numeric.k_integer_p;
import static org.jruby.util.Numeric.k_numeric_p;
import static org.jruby.util.Numeric.ldexp;
import static org.jruby.util.Numeric.nurat_rationalize_internal;

import java.io.IOException;
import java.math.RoundingMode;

import org.jcodings.specific.ASCIIEncoding;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.ast.util.ArgsUtil;
import org.jruby.common.IRubyWarnings;
import org.jruby.runtime.Arity;
import org.jruby.runtime.ClassIndex;
import org.jruby.runtime.JavaSites;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ObjectMarshal;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.callsite.RespondToCallSite;
import org.jruby.runtime.marshal.MarshalStream;
import org.jruby.runtime.marshal.UnmarshalStream;
import org.jruby.util.ByteList;
import org.jruby.util.Numeric;
import org.jruby.util.TypeConverter;

import static org.jruby.runtime.Helpers.invokedynamic;
import static org.jruby.runtime.invokedynamic.MethodNames.HASH;

/**
 *  1.9 rational.c as of revision: 20011
 */

@JRubyClass(name = "Rational", parent = "Numeric")
public class RubyRational extends RubyNumeric {
    
    public static RubyClass createRationalClass(Ruby runtime) {
        RubyClass rationalc = runtime.defineClass("Rational", runtime.getNumeric(), RATIONAL_ALLOCATOR);
        runtime.setRational(rationalc);

        rationalc.setClassIndex(ClassIndex.RATIONAL);
        rationalc.setReifiedClass(RubyRational.class);
        
        rationalc.kindOf = new RubyModule.JavaClassKindOf(RubyRational.class);

        rationalc.setMarshal(RATIONAL_MARSHAL);
        rationalc.defineAnnotatedMethods(RubyRational.class);

        rationalc.getSingletonClass().undefineMethod("allocate");
        rationalc.getSingletonClass().undefineMethod("new");

        return rationalc;
    }

    private static ObjectAllocator RATIONAL_ALLOCATOR = new ObjectAllocator() {
        @Override
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            RubyFixnum zero = RubyFixnum.zero(runtime);
            return new RubyRational(runtime, klass, zero, zero);
        }
    };

    /** internal
     * 
     */
    private RubyRational(Ruby runtime, IRubyObject clazz, IRubyObject num, IRubyObject den) {
        super(runtime, (RubyClass)clazz);
        this.num = num.convertToInteger();
        this.den = den.convertToInteger();
    }

    /** rb_rational_raw
     * 
     */
    public static RubyRational newRationalRaw(Ruby runtime, IRubyObject x, IRubyObject y) {
        return new RubyRational(runtime, runtime.getRational(), x, y);
    }

    /** rb_rational_raw1
     * 
     */
    static RubyRational newRationalRaw(Ruby runtime, IRubyObject x) {
        return new RubyRational(runtime, runtime.getRational(), x, RubyFixnum.one(runtime));
    }

    /** rb_rational_new1
     * 
     */
    static IRubyObject newRationalCanonicalize(ThreadContext context, IRubyObject x) {
        return newRationalCanonicalize(context, x, RubyFixnum.one(context.runtime));
    }

    /** rb_rational_new
     * 
     */
    public static IRubyObject newRationalCanonicalize(ThreadContext context, IRubyObject x, IRubyObject y) {
        return canonicalizeInternal(context, context.runtime.getRational(), x, y);
    }

    public static IRubyObject newRationalCanonicalize(ThreadContext context, long x, long y) {
        Ruby runtime = context.runtime;
        return canonicalizeInternal(context, runtime.getRational(), runtime.newFixnum(x), runtime.newFixnum(y));
    }
    
    /** f_rational_new2
     * 
     */
    private static IRubyObject newRational(ThreadContext context, IRubyObject clazz, IRubyObject x, IRubyObject y) {
        assert !(x instanceof RubyRational) && !(y instanceof RubyRational);
        return canonicalizeInternal(context, clazz, x, y);
    }

    /** f_rational_new_no_reduce2
     * 
     */
    private static IRubyObject newRationalNoReduce(ThreadContext context, IRubyObject clazz, IRubyObject x, IRubyObject y) {
        assert !(x instanceof RubyRational) && !(y instanceof RubyRational);
        return canonicalizeInternalNoReduce(context, clazz, x, y);
    }

    /** f_rational_new_bang2
     * 
     */
    private static RubyRational newRationalBang(ThreadContext context, IRubyObject clazz, IRubyObject x, IRubyObject y) { 
        assert !f_negative_p(context, y) && !(f_zero_p(context, y));
        return new RubyRational(context.runtime, clazz, x, y);
    }

    /** f_rational_new_bang1
     * 
     */
    private static RubyRational newRationalBang(ThreadContext context, IRubyObject clazz, IRubyObject x) {
        return newRationalBang(context, clazz, x, RubyFixnum.one(context.runtime));
    }

    @Override
    public ClassIndex getNativeClassIndex() {
        return ClassIndex.RATIONAL;
    }
    
    private RubyInteger num;
    private RubyInteger den;

    /** nurat_canonicalization
     *
     */
    private static boolean canonicalization = false;
    public static void setCanonicalization(boolean canonical) {
        canonicalization = canonical;
    }

    /** nurat_int_check
     * 
     */
    static void intCheck(ThreadContext context, IRubyObject num) {
        if (num instanceof RubyFixnum || num instanceof RubyBignum) return;
        if (!(num instanceof RubyNumeric) || !num.callMethod(context, "integer?").isTrue()) {
            Ruby runtime = num.getRuntime();
            throw runtime.newTypeError("can't convert "
                    + num.getMetaClass().getName() + " into Rational");
        }
    }

    /** nurat_int_value
     * 
     */
    static IRubyObject intValue(ThreadContext context, IRubyObject num) {
        intCheck(context, num);
        if (!(num instanceof RubyInteger)) num = num.callMethod(context, "to_f");
        return num;
    }
    
    /** nurat_s_canonicalize_internal
     * 
     */
    private static IRubyObject canonicalizeInternal(ThreadContext context, IRubyObject clazz, IRubyObject num, IRubyObject den) {
        // MRI: nurat_canonicalize, negation part
        if (canonicalizeShouldNegate(context, num, den)) {
            num = f_negate(context, num);
            den = f_negate(context, den);
        }

        IRubyObject gcd = f_gcd(context, num, den);
        num = f_idiv(context, num, gcd);
        den = f_idiv(context, den, gcd);

        if (Numeric.CANON && canonicalization && f_one_p(context, den)) {
            return num;
        }

        return new RubyRational(context.runtime, clazz, num, den);
    }

    /** nurat_s_canonicalize_internal_no_reduce
     * 
     */
    private static IRubyObject canonicalizeInternalNoReduce(ThreadContext context, IRubyObject clazz, IRubyObject num, IRubyObject den) {
        // MRI: nurat_canonicalize, negation part
        if (canonicalizeShouldNegate(context, num, den)) {
            num = f_negate(context, num);
            den = f_negate(context, den);
        }

        if (Numeric.CANON && canonicalization && f_one_p(context, den)) {
            return num;
        }

        return new RubyRational(context.runtime, clazz, num, den);
    }

    // MRI: nurat_canonicalize, value check part
    private static boolean canonicalizeShouldNegate(ThreadContext context, IRubyObject num, IRubyObject den) {
        Ruby runtime = context.runtime;
        IRubyObject res = f_cmp(context, den, RubyFixnum.zero(runtime));
        if (res == RubyFixnum.minus_one(runtime)) {
            return true;
        } else if (res == RubyFixnum.zero(runtime)) {
            throw runtime.newZeroDivisionError();
        }
        return false;
    }
    
    /** nurat_s_new
     * 
     */
    @Deprecated
    public static IRubyObject newInstance(ThreadContext context, IRubyObject clazz, IRubyObject[]args) {
        switch (args.length) {
        case 1: return newInstance(context, clazz, args[0]);
        case 2: return newInstance(context, clazz, args[0], args[1]);
        }
        Arity.raiseArgumentError(context.runtime, args.length, 1, 1);
        return null;
    }

    // @JRubyMethod(name = "new", meta = true, visibility = Visibility.PRIVATE)
    public static IRubyObject newInstance(ThreadContext context, IRubyObject clazz, IRubyObject num) {
        num = intValue(context, num);
        return canonicalizeInternal(context, clazz, num, RubyFixnum.one(context.runtime));
    }

    // @JRubyMethod(name = "new", meta = true, visibility = Visibility.PRIVATE)
    public static IRubyObject newInstance(ThreadContext context, IRubyObject clazz, IRubyObject num, IRubyObject den) {
        num = intValue(context, num);
        den = intValue(context, den);
        return canonicalizeInternal(context, clazz, num, den);
    }
    
    /** rb_Rational1
     * 
     */
    public static IRubyObject newRationalConvert(ThreadContext context, IRubyObject x) {
        return newRationalConvert(context, x, RubyFixnum.one(context.runtime));
    }

    /** rb_Rational/rb_Rational2
     * 
     */
    public static IRubyObject newRationalConvert(ThreadContext context, IRubyObject x, IRubyObject y) {
        return convert(context, context.runtime.getRational(), x, y);
    }
    
    public static RubyRational newRational(Ruby runtime, long x, long y) {
        return new RubyRational(runtime, runtime.getRational(), runtime.newFixnum(x), runtime.newFixnum(y));
    }
    
    @Deprecated
    public static IRubyObject convert(ThreadContext context, IRubyObject clazz, IRubyObject[]args) {
        switch (args.length) {
        case 1: return convert(context, clazz, args[0]);        
        case 2: return convert(context, clazz, args[0], args[1]);
        }
        Arity.raiseArgumentError(context.runtime, args.length, 1, 1);
        return null;
    }

    /** nurat_s_convert
     * 
     */
    @JRubyMethod(name = "convert", meta = true, visibility = Visibility.PRIVATE)
    public static IRubyObject convert(ThreadContext context, IRubyObject recv, IRubyObject a1) {
        if (a1.isNil()) {
            throw context.runtime.newTypeError("can't convert nil into Rational");
        }

        return convertCommon(context, recv, a1, context.runtime.getNil());
    }

    /** nurat_s_convert
     * 
     */
    @JRubyMethod(name = "convert", meta = true, visibility = Visibility.PRIVATE)
    public static IRubyObject convert(ThreadContext context, IRubyObject recv, IRubyObject a1, IRubyObject a2) {
        if (a1.isNil() || a2.isNil()) {
            throw context.runtime.newTypeError("can't convert nil into Rational");
        }
        
        return convertCommon(context, recv, a1, a2);
    }
    
    private static IRubyObject convertCommon(ThreadContext context, IRubyObject recv, IRubyObject a1, IRubyObject a2) {
        if (a1 instanceof RubyComplex) {
            RubyComplex a1c = (RubyComplex) a1;
            if (k_exact_p(a1c.getImage()) && f_zero_p(context, a1c.getImage())) a1 = a1c.getReal();
        }
        if (a2 instanceof RubyComplex) {
            RubyComplex a2c = (RubyComplex) a2;
            if (k_exact_p(a2c.getImage()) && f_zero_p(context, a2c.getImage())) a2 = a2c.getReal();
        }
        
        if (a1 instanceof RubyFloat) {
            a1 = f_to_r(context, a1);
        } else if (a1 instanceof RubyString) {
            a1 = str_to_r_strict(context, a1);
        } else {
            if (a1 instanceof RubyObject && responds_to_to_r(context, a1)) {
                a1 = f_to_r(context, a1);
            }
        }
        
        if (a2 instanceof RubyFloat) {
            a2 = f_to_r(context, a2);
        } else if (a2 instanceof RubyString) {
            a2 = str_to_r_strict(context, a2);
        }

        if (a1 instanceof RubyRational) {
            if (a2.isNil() || (k_exact_p(a2) && f_one_p(context, a2))) return a1;
        }

        if (a2.isNil()) {
            if (a1 instanceof RubyNumeric && !f_integer_p(context, a1).isTrue()) return a1;
            return newInstance(context, recv, a1);
        } else {
            if (a1 instanceof RubyNumeric && a2 instanceof RubyNumeric &&
                (!f_integer_p(context, a1).isTrue() || !f_integer_p(context, a2).isTrue())) {
                return f_div(context, a1, a2);
            }
            return newInstance(context, recv, a1, a2);
        }
    }

    private static boolean responds_to_to_r(ThreadContext context, IRubyObject obj) {
        return respond_to_to_r.respondsTo(context, obj, obj);
    }

    // TODO: wasn't sure whether to put this on NumericSites, here for now - should move
    static final RespondToCallSite respond_to_to_r = new RespondToCallSite("to_r");

    /** nurat_numerator
     * 
     */
    @JRubyMethod(name = "numerator")
    @Override
    public IRubyObject numerator(ThreadContext context) {
        return num;
    }

    /** nurat_denominator
     * 
     */
    @JRubyMethod(name = "denominator")
    @Override
    public IRubyObject denominator(ThreadContext context) {
        return den;
    }

    public IRubyObject getNumerator() {
        return num;
    }

    public IRubyObject getDenominator() {
        return den;
    }

    /** f_imul
     * 
     */
    private static IRubyObject f_imul(ThreadContext context, long a, long b) {
        Ruby runtime = context.runtime;
        if (a == 0 || b == 0) {
            return RubyFixnum.zero(runtime);
        } else if (a == 1) {
            return RubyFixnum.newFixnum(runtime, b);
        } else if (b == 1) {
            return RubyFixnum.newFixnum(runtime, a);
        }

        long c = a * b;
        if(c / a != b) {
            return RubyBignum.newBignum(runtime, a).op_mul(context, RubyBignum.newBignum(runtime, b));
        }
        return RubyFixnum.newFixnum(runtime, c);
    }
    
    /** f_addsub
     * 
     */
    private IRubyObject f_addsub(ThreadContext context, IRubyObject anum, IRubyObject aden, IRubyObject bnum, IRubyObject bden, boolean plus) {
        Ruby runtime = context.runtime;
        IRubyObject newNum, newDen, g, a, b;
        if (anum instanceof RubyFixnum && aden instanceof RubyFixnum &&
            bnum instanceof RubyFixnum && bden instanceof RubyFixnum) {
            long an = ((RubyFixnum)anum).getLongValue();
            long ad = ((RubyFixnum)aden).getLongValue();
            long bn = ((RubyFixnum)bnum).getLongValue();
            long bd = ((RubyFixnum)bden).getLongValue();
            long ig = i_gcd(ad, bd);

            g = RubyFixnum.newFixnum(runtime, ig);
            a = f_imul(context, an, bd / ig);
            b = f_imul(context, bn, ad / ig);
        } else {
            g = f_gcd(context, aden, bden);
            a = f_mul(context, anum, f_idiv(context, bden, g));
            b = f_mul(context, bnum, f_idiv(context, aden, g));
        }

        IRubyObject c = plus ? f_add(context, a, b) : f_sub(context, a, b);

        b = f_idiv(context, aden, g);
        g = f_gcd(context, c, g);
        newNum = f_idiv(context, c, g);
        a = f_idiv(context, bden, g);
        newDen = f_mul(context, a, b);
        
        return RubyRational.newRationalNoReduce(context, getMetaClass(), newNum, newDen);
    }
    
    /** nurat_add
     * 
     */
    @JRubyMethod(name = "+")
    public IRubyObject op_add(ThreadContext context, IRubyObject other) {
        if (other instanceof RubyFixnum || other instanceof RubyBignum) {
            return f_addsub(context, num, den, other, RubyFixnum.one(context.runtime), true);
        } else if (other instanceof RubyFloat) {
            return f_add(context, f_to_f(context, this), other);
        } else if (other instanceof RubyRational) {
            RubyRational otherRational = (RubyRational)other;
            return f_addsub(context, num, den, otherRational.num, otherRational.den, true);
        }            
        return coerceBin(context, sites(context).op_plus, other);
    }
    
    /** nurat_sub
     * 
     */
    @JRubyMethod(name = "-")
    public IRubyObject op_sub(ThreadContext context, IRubyObject other) {
        if (other instanceof RubyFixnum || other instanceof RubyBignum) {
            return f_addsub(context, num, den, other, RubyFixnum.one(context.runtime), false);
        } else if (other instanceof RubyFloat) {
            return f_sub(context, f_to_f(context, this), other);
        } else if (other instanceof RubyRational) {
            RubyRational otherRational = (RubyRational)other;
            return f_addsub(context, num, den, otherRational.num, otherRational.den, false);
        }
        return coerceBin(context, sites(context).op_minus, other);
    }
    
    /** f_muldiv
     * 
     */
    private IRubyObject f_muldiv(ThreadContext context, IRubyObject anum, IRubyObject aden, IRubyObject bnum, IRubyObject bden, boolean mult) {
        if (!mult) {
            if (f_negative_p(context, bnum)) {
                anum = f_negate(context, anum);
                bnum = f_negate(context, bnum);
            }
            IRubyObject tmp =  bnum;
            bnum = bden;
            bden = tmp;
        }
        
        final IRubyObject newNum, newDen;
        if (anum instanceof RubyFixnum && aden instanceof RubyFixnum &&
            bnum instanceof RubyFixnum && bden instanceof RubyFixnum) {
            long an = ((RubyFixnum)anum).getLongValue();
            long ad = ((RubyFixnum)aden).getLongValue();
            long bn = ((RubyFixnum)bnum).getLongValue();
            long bd = ((RubyFixnum)bden).getLongValue();
            long g1 = i_gcd(an, bd);
            long g2 = i_gcd(ad, bn);
            
            newNum = f_imul(context, an / g1, bn / g2);
            newDen = f_imul(context, ad / g2, bd / g1);
        } else {
            IRubyObject g1 = f_gcd(context, anum, bden); 
            IRubyObject g2 = f_gcd(context, aden, bnum);
            
            newNum = f_mul(context, f_idiv(context, anum, g1), f_idiv(context, bnum, g2));
            newDen = f_mul(context, f_idiv(context, aden, g2), f_idiv(context, bden, g1));
        }

        return RubyRational.newRationalNoReduce(context, getMetaClass(), newNum, newDen);
    }

    /** nurat_mul
     * 
     */
    @JRubyMethod(name = "*")
    public IRubyObject op_mul(ThreadContext context, IRubyObject other) {
        if (other instanceof RubyFixnum || other instanceof RubyBignum) {
            return f_muldiv(context, num, den, other, RubyFixnum.one(context.runtime), true);
        } else if (other instanceof RubyFloat) {
            return f_mul(context, f_to_f(context, this), other);
        } else if (other instanceof RubyRational) {
            RubyRational otherRational = (RubyRational)other;
            return f_muldiv(context, num, den, otherRational.num, otherRational.den, true);
        }
        return coerceBin(context, sites(context).op_times, other);
    }
    
    /** nurat_div
     * 
     */
    @JRubyMethod(name = {"/", "quo"})
    public IRubyObject op_div(ThreadContext context, IRubyObject other) {
        if (other instanceof RubyFixnum || other instanceof RubyBignum) {
            if (f_zero_p(context, other)) {
                throw context.runtime.newZeroDivisionError();
            }
            return f_muldiv(context, num, den, other, RubyFixnum.one(context.runtime), false);
        } else if (other instanceof RubyFloat) {
            return f_to_f(context, this).callMethod(context, "/", other);
        } else if (other instanceof RubyRational) {
            if (f_zero_p(context, other)) {
                throw context.runtime.newZeroDivisionError();
            }
            RubyRational otherRational = (RubyRational)other;
            return f_muldiv(context, num, den, otherRational.num, otherRational.den, false);
        }
        return coerceBin(context, sites(context).op_quo, other);
    }

    /** nurat_fdiv
     * 
     */
    @JRubyMethod(name = "fdiv")
    public IRubyObject op_fdiv(ThreadContext context, IRubyObject other) {
        return f_div(context, f_to_f(context, this), other);
    }

    /** nurat_expt
     * 
     */
    @JRubyMethod(name = "**")
    public IRubyObject op_expt(ThreadContext context, IRubyObject other) {
        Ruby runtime = context.runtime;

        if (k_exact_p(other) && f_zero_p(context, other)) {
            return RubyRational.newRationalBang(context, getMetaClass(), RubyFixnum.one(runtime));
        }

        if (other instanceof RubyRational) {
            RubyRational otherRational = (RubyRational)other;
            if (f_one_p(context, otherRational.den)) other = otherRational.num;
        }

        // Deal with special cases of 0**n and 1**n
        if (k_numeric_p(other) && k_exact_p(other)) {
            if (f_one_p(context, den)) {
                if (f_one_p(context, num)) {
                    return RubyRational.newRationalBang(context, getMetaClass(), RubyFixnum.one(runtime));
                }
                if (f_minus_one_p(context, num) && k_integer_p(other)) {
                    return RubyRational.newRationalBang(context, getMetaClass(),
                            f_odd_p(context, other) ? RubyFixnum.minus_one(runtime) : RubyFixnum.one(runtime));
                }
                if (f_zero_p(context, num)) {
                    if (f_negative_p(context, other)) throw context.runtime.newZeroDivisionError();
                    return RubyRational.newRationalBang(context, getMetaClass(), RubyFixnum.zero(runtime));
                }
            }
        }

        // General case
        if (other instanceof RubyFixnum || other instanceof RubyBignum) {
            return fix_expt(context, (RubyInteger) other, ((RubyInteger) other).signum());
        }
        if (other instanceof RubyFloat || other instanceof RubyRational) {
            return f_expt(context, f_to_f(context, this), other);
        }
        return coerceBin(context, sites(context).op_exp, other);
    }

    public final IRubyObject op_expt(ThreadContext context, long other) {
        Ruby runtime = context.runtime;

        if (other == 0) {
            return RubyRational.newRationalBang(context, getMetaClass(), RubyFixnum.one(runtime));
        }

        // Deal with special cases of 0**n and 1**n
        if (f_one_p(context, den)) {
            if (f_one_p(context, num)) {
                return RubyRational.newRationalBang(context, getMetaClass(), RubyFixnum.one(runtime));
            }
            if (f_minus_one_p(context, num)) {
                return RubyRational.newRationalBang(context, getMetaClass(),
                        other % 2 != 0 ? RubyFixnum.minus_one(runtime) : RubyFixnum.one(runtime));
            }
            if (f_zero_p(context, num)) {
                if (other < 0) throw context.runtime.newZeroDivisionError();
                return RubyRational.newRationalBang(context, getMetaClass(), RubyFixnum.zero(runtime));
            }
        }

        // General case
        return fix_expt(context, RubyFixnum.newFixnum(runtime, other), Long.signum(other));
    }

    private IRubyObject fix_expt(ThreadContext context, RubyInteger other, final int sign) {
        final IRubyObject tnum, tden;
        if (sign > 0) { // other > 0
            tnum = f_expt(context, num, other);
            tden = f_expt(context, den, other);
        } else if (sign < 0) { // other < 0
            tnum = f_expt(context, den, f_negate(context, other));
            tden = f_expt(context, num, f_negate(context, other));
        } else { // other == 0
            tnum = tden = RubyFixnum.one(context.runtime);
        }
        return RubyRational.newRational(context, getMetaClass(), tnum, tden);
    }

    /** nurat_cmp
     * 
     */
    @JRubyMethod(name = "<=>")
    @Override
    public IRubyObject op_cmp(ThreadContext context, IRubyObject other) {
        if (other instanceof RubyFixnum || other instanceof RubyBignum) {
            if (den instanceof RubyFixnum && ((RubyFixnum)den).getLongValue() == 1) return f_cmp(context, num, other);
            return f_cmp(context, this, RubyRational.newRationalBang(context, getMetaClass(), other));
        } else if (other instanceof RubyFloat) {
            return f_cmp(context, f_to_f(context, this), other);
        } else if (other instanceof RubyRational) {
            RubyRational otherRational = (RubyRational)other;
            final IRubyObject num1, num2;
            if (num instanceof RubyFixnum && den instanceof RubyFixnum &&
                otherRational.num instanceof RubyFixnum && otherRational.den instanceof RubyFixnum) {
                num1 = f_imul(context, ((RubyFixnum)num).getLongValue(), ((RubyFixnum)otherRational.den).getLongValue());
                num2 = f_imul(context, ((RubyFixnum)otherRational.num).getLongValue(), ((RubyFixnum)den).getLongValue());
            } else {
                num1 = f_mul(context, num, otherRational.den);
                num2 = f_mul(context, otherRational.num, den);
            }
            return f_cmp(context, f_sub(context, num1, num2), RubyFixnum.zero(context.runtime));
        }
        return coerceBin(context, sites(context).op_cmp, other);
    }

    /** nurat_equal_p
     * 
     */
    @JRubyMethod(name = "==")
    @Override
    public IRubyObject op_equal(ThreadContext context, IRubyObject other) {
        Ruby runtime = context.runtime;
        if (other instanceof RubyFixnum || other instanceof RubyBignum) {
            if (f_zero_p(context, num) && f_zero_p(context, other)) return runtime.getTrue();
            if (!(den instanceof RubyFixnum) || ((RubyFixnum)den).getLongValue() != 1) return runtime.getFalse();
            return f_equal(context, num, other);
        } else if (other instanceof RubyFloat) {
            return f_equal(context, f_to_f(context, this), other);
        } else if (other instanceof RubyRational) {
            RubyRational otherRational = (RubyRational)other;
            if (f_zero_p(context, num) && f_zero_p(context, otherRational.num)) return runtime.getTrue();
            return runtime.newBoolean(f_equal(context, num, otherRational.num).isTrue() &&
                    f_equal(context, den, otherRational.den).isTrue());

        }
        return f_equal(context, other, this);
    }

    /** nurat_coerce
     * 
     */
    @JRubyMethod(name = "coerce")
    public IRubyObject op_coerce(ThreadContext context, IRubyObject other) {
        Ruby runtime = context.runtime;
        if (other instanceof RubyFixnum || other instanceof RubyBignum) {
            return runtime.newArray(RubyRational.newRationalBang(context, getMetaClass(), other), this);
        } else if (other instanceof RubyFloat) {
            return runtime.newArray(other, f_to_f(context, this));
        } else if (other instanceof RubyRational) {
            return runtime.newArray(other, this);
        }
        throw runtime.newTypeError(other.getMetaClass() + " can't be coerced into " + getMetaClass());
    }

    /** nurat_idiv
     * 
     */
    @JRubyMethod(name = "div")
    @Override
    public IRubyObject idiv(ThreadContext context, IRubyObject other) {
        if (num2dbl(other) == 0.0) throw context.runtime.newZeroDivisionError();

        return f_floor(context, f_div(context, this, other));
    }

    /** nurat_mod
     * 
     */
    @JRubyMethod(name = {"modulo", "%"})
    public IRubyObject op_mod(ThreadContext context, IRubyObject other) {
        if (num2dbl(other) == 0.0) throw context.runtime.newZeroDivisionError();

        return f_sub(context, this, f_mul(context, other, f_floor(context, f_div(context, this, other))));
    }

    @Deprecated
    public IRubyObject op_mod19(ThreadContext context, IRubyObject other) {
        return op_mod(context, other);
    }

    /** nurat_divmod
     * 
     */
    @JRubyMethod(name = "divmod")
    public IRubyObject op_divmod(ThreadContext context, IRubyObject other) {
        if (num2dbl(other) == 0.0) throw context.runtime.newZeroDivisionError();

        IRubyObject val = f_floor(context, f_div(context, this, other));
        return context.runtime.newArray(val, f_sub(context, this, f_mul(context, other, val)));
    }

    @Deprecated
    public IRubyObject op_divmod19(ThreadContext context, IRubyObject other) {
        return op_divmod(context, other);
    }

    /** nurat_rem
     * 
     */
    @JRubyMethod(name = "remainder")
    public IRubyObject op_rem(ThreadContext context, IRubyObject other) {
        IRubyObject val = f_truncate(context, f_div(context, this, other));
        return f_sub(context, this, f_mul(context, other, val));
    }

    /** nurat_abs
     * 
     */
    @JRubyMethod(name = "abs")
    public IRubyObject op_abs(ThreadContext context) {
        if (!f_negative_p(context, this)) return this;
        return f_negate(context, this);
    }

    /**
     * MRI: nurat_floor_n
     */
    @JRubyMethod(name = "floor")
    public IRubyObject floor(ThreadContext context) {
        return roundCommon(context, context.nil, RoundingMode.FLOOR);
    }

    @JRubyMethod(name = "floor")
    public IRubyObject floor(ThreadContext context, IRubyObject n) {
        return roundCommon(context, n, RoundingMode.FLOOR);
    }

    // MRI: nurat_floor
    private IRubyObject mriFloor(ThreadContext context) {
        return num.idiv(context, den);
    }

    /**
     * MRI: nurat_ceil_n
     */
    @Override
    @JRubyMethod(name = "ceil")
    public IRubyObject ceil(ThreadContext context) {
        return roundCommon(context, context.nil, RoundingMode.CEILING);
    }

    @JRubyMethod(name = "ceil")
    public IRubyObject ceil(ThreadContext context, IRubyObject n) {
        return roundCommon(context, n, RoundingMode.CEILING);
    }

    // MRI: nurat_ceil
    private IRubyObject mriCeil(ThreadContext context) {
        return ((RubyInteger) ((RubyInteger) num.op_uminus()).idiv(context, den)).op_uminus();
    }
    
    @JRubyMethod(name = "to_i")
    public IRubyObject to_i(ThreadContext context) {
        return truncate(context);
    }

    /**
     * MRI: nurat_truncate
     */
    @JRubyMethod(name = "truncate")
    public IRubyObject truncate(ThreadContext context) {
        return roundCommon(context, context.nil, RoundingMode.UNNECESSARY);
    }

    @JRubyMethod(name = "truncate")
    public IRubyObject truncate(ThreadContext context, IRubyObject n) {
        return roundCommon(context, n, RoundingMode.UNNECESSARY);
    }

    private IRubyObject mriTruncate(ThreadContext context) {
        if (num.isNegative(context).isTrue()) {
            return ((RubyInteger) ((RubyInteger) num.op_uminus()).idiv(context, den)).op_uminus();
        }
        return num.idiv(context, den);
    }

    @JRubyMethod(name = "round")
    public IRubyObject round(ThreadContext context) {
        return roundCommon(context, context.nil, RoundingMode.HALF_UP);
    }

    @JRubyMethod(name = "round")
    public IRubyObject round(ThreadContext context, IRubyObject n) {
        Ruby runtime = context.runtime;

        IRubyObject opts = ArgsUtil.getOptionsArg(runtime, n);
        if (!opts.isNil()) {
            n = context.nil;
        }

        RoundingMode mode = RubyNumeric.getRoundingMode(context, opts);

        return roundCommon(context, n, mode);
    }

    @JRubyMethod(name = "round")
    public IRubyObject round(ThreadContext context, IRubyObject n, IRubyObject opts) {
        Ruby runtime = context.runtime;

        opts = ArgsUtil.getOptionsArg(runtime, opts);
        if (!opts.isNil()) {
            n = context.nil;
        }

        RoundingMode mode = RubyNumeric.getRoundingMode(context, opts);

        return roundCommon(context, n, mode);
    }

    // MRI: f_round_common
    private IRubyObject roundCommon(ThreadContext context, IRubyObject n, RoundingMode mode) {
        Ruby runtime = context.runtime;
        IRubyObject b, s;

        if (n.isNil()) {
            return doRound(context, mode);
        }

        if (!(n instanceof RubyInteger)) {
            throw runtime.newTypeError(n, getRuntime().getInteger());
        }

        b = runtime.newFixnum(10).op_pow(context, n);
        s = this.op_mul(context, b);

        if (s instanceof RubyFloat) {
            if (((RubyInteger) n).isNegative(context).isTrue()) {
                return RubyFixnum.zero(runtime);
            }
            return this;
        }

        RubyClass metaClass = getMetaClass();
        RubyFixnum one = RubyFixnum.one(runtime);
        if (!(s instanceof RubyRational)) {
            s = RubyRational.newRational(context, metaClass, s, one);
        }

        s = ((RubyRational) s).doRound(context, mode);

        s = RubyRational.newRational(context, metaClass, s, one);
        s = ((RubyRational) s).op_div(context, b);

        if (s instanceof RubyRational && ((RubyInteger) n).op_cmp(context, one).convertToInteger().getLongValue() < 0) {
            s = ((RubyRational) s).truncate(context);
        }

        return s;
    }

    private IRubyObject doRound(ThreadContext context, RoundingMode mode) {
        switch (mode) {
            case HALF_UP:
                return roundHalfUp(context);
            case HALF_EVEN:
                return roundHalfEven(context);
            case HALF_DOWN:
                return roundHalfDown(context);
            case FLOOR:
                return mriFloor(context);
            case CEILING:
                return mriCeil(context);
            case UNNECESSARY:
                return mriTruncate(context);
            default:
                throw context.runtime.newRuntimeError("BUG: invalid rounding mode: " + mode);
        }
    }

    // MRI: nurat_round_half_down
    private IRubyObject roundHalfDown(ThreadContext context) {
        Ruby runtime = context.runtime;

        RubyInteger num = this.num, den = this.den;
        IRubyObject neg;

        neg = num.isNegative(context);

        if (neg.isTrue()) {
            num = (RubyInteger) num.op_uminus();
        }

        num = (RubyInteger) ((RubyInteger) num.op_mul(context, RubyFixnum.two(runtime))).op_plus(context, den);
        num = (RubyInteger) num.op_minus(context, RubyFixnum.one(runtime));
        den = (RubyInteger) den.op_mul(context, RubyFixnum.two(runtime));
        num = (RubyInteger) num.idiv(context, den);

        if (neg.isTrue())
            num = (RubyInteger) num.op_uminus();

        return num;
    }

    // MRI: nurat_round_half_even
    private IRubyObject roundHalfEven(ThreadContext context) {
        Ruby runtime = context.runtime;

        RubyInteger num = this.num, den = this.den;
        IRubyObject neg;
        RubyArray qr;

        neg = num.isNegative(context);

        if (neg.isTrue()) {
            num = (RubyInteger) num.op_uminus(context);
        }

        num = (RubyInteger) ((RubyInteger) num.op_mul(context, RubyFixnum.two(runtime))).op_plus(context, den);
        den = (RubyInteger) den.op_mul(context, RubyFixnum.two(runtime));
        qr = (RubyArray) num.divmod(context, den);
        num = (RubyInteger) qr.eltOk(0);
        if (((RubyInteger) qr.eltOk(1)).zero_p(context).isTrue()) {
            num = (RubyInteger) num.op_and(context, RubyFixnum.newFixnum(runtime, ~1L));
        }

        if (neg.isTrue()) {
            num = (RubyInteger) num.op_uminus(context);
        }

        return num;
    }

    // MRI: nurat_round_half_up
    private IRubyObject roundHalfUp(ThreadContext context) {
        Ruby runtime = context.runtime;

        RubyInteger num = this.num, den = this.den;
        IRubyObject neg;

        neg = num.isNegative(context);

        if (neg.isTrue()) {
            num = (RubyInteger) num.op_uminus();
        }

        num = (RubyInteger) ((RubyInteger) num.op_mul(context, RubyFixnum.two(runtime))).op_plus(context, den);
        den = (RubyInteger) den.op_mul(context, RubyFixnum.two(runtime));
        num = (RubyInteger) num.idiv(context, den);

        if (neg.isTrue()) {
            num = (RubyInteger) num.op_uminus();
        }

        return num;
    }

    /** nurat_to_f
     * 
     */
    private static long ML = (long)(Math.log(Double.MAX_VALUE) / Math.log(2.0) - 1);
    @JRubyMethod(name = "to_f")
    public IRubyObject to_f(ThreadContext context) {
        return context.runtime.newFloat(getDoubleValue(context));
    }
    
    @Override
    public double getDoubleValue() {
        return getDoubleValue(getRuntime().getCurrentContext());
    }
    
    public double getDoubleValue(ThreadContext context) {
        Ruby runtime = context.runtime;
        if (f_zero_p(context, num)) return 0;

        IRubyObject myNum = this.num;
        IRubyObject myDen = this.den;

        boolean minus = false;
        if (f_negative_p(context, myNum)) {
            myNum = f_negate(context, myNum);
            minus = true;
        }

        long nl = i_ilog2(context, myNum);
        long dl = i_ilog2(context, myDen);

        long ne = 0;
        if (nl > ML) {
            ne = nl - ML;
            myNum = f_rshift(context, myNum, RubyFixnum.newFixnum(runtime, ne));
        }

        long de = 0;
        if (dl > ML) {
            de = dl - ML;
            myDen = f_rshift(context, myDen, RubyFixnum.newFixnum(runtime, de));
        }

        long e = ne - de;

        if (e > 1023 || e < -1022) {
            runtime.getWarnings().warn(IRubyWarnings.ID.FLOAT_OUT_OF_RANGE, "out of Float range");
            return e > 0 ? Double.MAX_VALUE : 0;
        }

        double f = RubyNumeric.num2dbl(myNum) / RubyNumeric.num2dbl(myDen);

        if (minus) f = -f;

        f = ldexp(f, e);

        if (Double.isInfinite(f) || Double.isNaN(f)) {
            runtime.getWarnings().warn(IRubyWarnings.ID.FLOAT_OUT_OF_RANGE, "out of Float range");
        }

        return f;
    }

    /** nurat_to_r
     * 
     */
    @JRubyMethod(name = "to_r")
    public IRubyObject to_r(ThreadContext context) {
        return this;
    }

    /** nurat_rationalize
     *
     */
    @JRubyMethod(name = "rationalize", optional = 1)
    public IRubyObject rationalize(ThreadContext context, IRubyObject[] args) {

        IRubyObject a, b;

        if (args.length == 0) return to_r(context);

        if (f_negative_p(context, this)) {
            return f_negate(context,
                    ((RubyRational) f_abs(context, this)).rationalize(context, args));
        }

        IRubyObject eps = f_abs(context, args[0]);
        a = f_sub(context, this, eps);
        b = f_add(context, this, eps);

        if (f_equal(context, a, b).isTrue()) return this;
        IRubyObject[] ary = new IRubyObject[2];
        ary[0] = a;
        ary[1] = b;
        IRubyObject[] ans = nurat_rationalize_internal(context, ary);

        return newRational(context, this.metaClass, ans[0], ans[1]);
    }

    /** nurat_hash
     * 
     */
    @JRubyMethod(name = "hash")
    public IRubyObject hash(ThreadContext context) {
        return f_xor(context, invokedynamic(context, num, HASH), invokedynamic(context, den, HASH));
    }

    /** nurat_to_s
     * 
     */
    @JRubyMethod(name = "to_s")
    public IRubyObject to_s(ThreadContext context) {
        RubyString str = RubyString.newEmptyString(context.getRuntime());
        str.append(f_to_s(context, num));
        str.cat((byte)'/');
        str.append(f_to_s(context, den));
        return str;
    }

    /** nurat_inspect
     * 
     */
    @JRubyMethod(name = "inspect")
    public IRubyObject inspect(ThreadContext context) {
        RubyString str = RubyString.newEmptyString(context.getRuntime());
        str.cat((byte)'(');
        str.append(f_inspect(context, num));
        str.cat((byte)'/');
        str.append(f_inspect(context, den));
        str.cat((byte)')');
        return str;
    }

    /** nurat_marshal_dump
     * 
     */
    @JRubyMethod(name = "marshal_dump")
    public IRubyObject marshal_dump(ThreadContext context) {
        RubyArray dump = context.runtime.newArray(num, den);
        if (hasVariables()) dump.syncVariables(this);
        return dump;
    }

    /** nurat_marshal_load
     * 
     */
    @JRubyMethod(name = "marshal_load")
    public IRubyObject marshal_load(ThreadContext context, IRubyObject arg) {
        RubyArray load = arg.convertToArray();
        IRubyObject num = load.size() > 0 ? load.eltInternal(0) : context.nil;
        IRubyObject den = load.size() > 1 ? load.eltInternal(1) : context.nil;

        intCheck(context, num);
        intCheck(context, den);

        // MRI: nurat_canonicalize, negation part
        if (canonicalizeShouldNegate(context, num, den)) {
            num = f_negate(context, num);
            den = f_negate(context, den);
        }

        this.num = (RubyInteger) num;
        this.den = (RubyInteger) den;

        if (load.hasVariables()) syncVariables((IRubyObject)load);
        return this;
    }

    private static final ObjectMarshal RATIONAL_MARSHAL = new ObjectMarshal() {
        @Override
        public void marshalTo(Ruby runtime, Object obj, RubyClass type,
                              MarshalStream marshalStream) throws IOException {
            throw runtime.newTypeError("marshal_dump should be used instead for Rational");
        }

        @Override
        public Object unmarshalFrom(Ruby runtime, RubyClass type,
                                    UnmarshalStream unmarshalStream) throws IOException {
            ThreadContext context = runtime.getCurrentContext();

            RubyRational r = (RubyRational) RubyClass.DEFAULT_OBJECT_MARSHAL.unmarshalFrom(runtime, type, unmarshalStream);
            IRubyObject num = r.removeInstanceVariable("@numerator");
            IRubyObject den = r.removeInstanceVariable("@denominator");

            intCheck(context, num);
            intCheck(context, den);

            // MRI: nurat_canonicalize, negation part
            if (canonicalizeShouldNegate(context, num, den)) {
                num = f_negate(context, num);
                den = f_negate(context, den);
            }

            r.num = (RubyInteger) num;
            r.den = (RubyInteger) den;

            return r;
        }
    };

    static RubyArray str_to_r_internal(ThreadContext context, IRubyObject recv) {
        RubyString s = recv.convertToString();
        ByteList bytes = s.getByteList();

        Ruby runtime = context.runtime;
        if (bytes.getRealSize() == 0) return runtime.newArray(runtime.getNil(), recv);

        IRubyObject m = RubyRegexp.newDummyRegexp(runtime, Numeric.RationalPatterns.rat_pat).match_m(context, s, false);
        
        if (!m.isNil()) {
            RubyMatchData match = (RubyMatchData)m;
            IRubyObject si = match.at(1);
            RubyString nu = (RubyString)match.at(2);
            IRubyObject de = match.at(3);
            IRubyObject re = match.post_match(context);
            
            RubyArray a = nu.split19(RubyRegexp.newDummyRegexp(runtime, Numeric.RationalPatterns.an_e_pat), context, false).convertToArray();
            RubyString ifp = (RubyString)a.eltInternal(0);
            IRubyObject exp = a.size() != 2 ? runtime.getNil() : a.eltInternal(1);
            
            a = ifp.split19(RubyRegexp.newDummyRegexp(runtime, Numeric.RationalPatterns.a_dot_pat), context, false).convertToArray();
            IRubyObject ip = a.eltInternal(0);
            IRubyObject fp = a.size() != 2 ? runtime.getNil() : a.eltInternal(1);
            
            IRubyObject v = RubyRational.newRationalCanonicalize(context, f_to_i(context, ip));
            
            if (!fp.isNil()) {
                bytes = fp.convertToString().getByteList();
                int count = 0;
                byte[]buf = bytes.getUnsafeBytes();
                int i = bytes.getBegin();
                int end = i + bytes.getRealSize();

                while (i < end) {
                    if (ASCIIEncoding.INSTANCE.isDigit(buf[i])) count++;
                    i++;
                }

                IRubyObject l = f_expt(context, RubyFixnum.newFixnum(runtime, 10), RubyFixnum.newFixnum(runtime, count));
                v = f_mul(context, v, l);
                v = f_add(context, v, f_to_i(context, fp));
                v = f_div(context, v, l);
            }

            if (!si.isNil()) {
                ByteList siBytes = si.convertToString().getByteList();
                if (siBytes.length() > 0 && siBytes.get(0) == '-') v = f_negate(context, v); 
            }

            if (!exp.isNil()) {
                v = f_mul(context, v, f_expt(context, RubyFixnum.newFixnum(runtime, 10), f_to_i(context, exp)));
            }

            if (!de.isNil()) {
                v = f_div(context, v, f_to_i(context, de));
            }
            return runtime.newArray(v, re);
        }
        return runtime.newArray(runtime.getNil(), recv);
    }
    
    private static IRubyObject str_to_r_strict(ThreadContext context, IRubyObject recv) {
        RubyArray a = str_to_r_internal(context, recv);
        if (a.eltInternal(0).isNil() || a.eltInternal(1).convertToString().getByteList().length() > 0) {
            IRubyObject s = recv.callMethod(context, "inspect");
            throw context.runtime.newArgumentError("invalid value for convert(): " + s.convertToString());
        }
        return a.eltInternal(0);
    }

    /**
     * numeric_quo
     */
    public static IRubyObject numericQuo(ThreadContext context, IRubyObject x, IRubyObject y) {
        if (y instanceof RubyFloat) {
            return ((RubyNumeric)x).fdiv(context, y);
        }

        if (Numeric.CANON && canonicalization) {
            x = newRationalRaw(context.runtime, x);
        } else {
            x = TypeConverter.convertToType(context, x, context.runtime.getRational(), sites(context).to_r_checked);
        }
        return sites(context).op_quo.call(context, x, x, y);
    }

    @Deprecated
    public IRubyObject op_floor(ThreadContext context) {
        return floor(context);
    }

    @Deprecated
    public IRubyObject op_floor(ThreadContext context, IRubyObject n) {
        return floor(context, n);
    }

    @Deprecated
    public IRubyObject op_ceil(ThreadContext context) {
        return ceil(context);
    }

    @Deprecated
    public IRubyObject op_ceil(ThreadContext context, IRubyObject n) {
        return ceil(context, n);
    }

    @Deprecated
    public IRubyObject op_idiv19(ThreadContext context, IRubyObject other) {
        return idiv(context, other);
    }

    @Deprecated
    public IRubyObject op_idiv(ThreadContext context, IRubyObject other) {
        return idiv(context, other);
    }

    private static JavaSites.RationalSites sites(ThreadContext context) {
        return context.sites.Rational;
    }
}
