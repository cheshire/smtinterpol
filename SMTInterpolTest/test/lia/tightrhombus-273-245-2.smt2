(set-info :source |A tight rhombus without a feasible integer solution.  This
benchmark is designed to be hard for the algorithm by Dillig, Dillig, and Aiken.
Authors: The SMTInterpol team|)
(set-info :category "crafted")
(set-info :status unsat)
(set-logic QF_LIA)
(declare-fun x () Int)
(declare-fun y () Int)
(assert (and 
	(<= 0 (- (* 273000 x) (* 245001 y)))
	(<= (- (* 273000 x) (* 245001 y)) 999)
	(<= 1 (- (* 273001 x) (* 245000 y)))
	(<= (- (* 273001 x) (* 245000 y)) 1000)))
(check-sat)
(exit)
