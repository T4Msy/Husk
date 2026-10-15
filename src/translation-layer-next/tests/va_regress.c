/* Portable regression for Husk's AAPCS64 va_list reader (husk-tl-va.h: tl_va_arg_u64/f64).
   Builds synthetic frames exactly as AAPCS64 va_start leaves them for mixed, register-overflowing
   variadic calls (3 named GP args: env,obj,mid), then checks Husk reads each argument back in order.
   Expected = AAPCS64 placement = what the Android runtime produces. Pure C over the frame; no arm64. */
#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef struct tl_va_list { void *stack, *gr_top, *vr_top; int gr_offs, vr_offs; } tl_va_list;
typedef struct tl_va_frame { uint64_t gp[8]; unsigned __int128 fp[8]; void *stack; } tl_va_frame;
static void tl_va_start(tl_va_frame *f,int ng,int nf,tl_va_list *o){
    o->stack=f->stack; o->gr_top=&f->gp[8]; o->vr_top=&f->fp[8];
    o->gr_offs=-(8-ng)*8; o->vr_offs=-(8-nf)*16; }
static uint64_t gnext(tl_va_list *ap){
    if(ap->gr_offs<0){uint64_t v=*(uint64_t*)((char*)ap->gr_top+ap->gr_offs);ap->gr_offs+=8;return v;}
    uint64_t v=*(uint64_t*)ap->stack; ap->stack=(char*)ap->stack+8; return v; }
static double fnext(tl_va_list *ap){
    if(ap->vr_offs<0){double d;memcpy(&d,(char*)ap->vr_top+ap->vr_offs,8);ap->vr_offs+=16;return d;}
    double d; memcpy(&d,ap->stack,8); ap->stack=(char*)ap->stack+8; return d; }

static int fails=0;
#define CKU(got,exp,msg) do{uint64_t g=(got),e=(exp); if(g!=e){printf("FAIL %s: got %#llx exp %#llx\n",msg,(unsigned long long)g,(unsigned long long)e);fails++;}}while(0)
#define CKD(got,exp,msg) do{double g=(got),e=(exp); if(g!=e){printf("FAIL %s: got %f exp %f\n",msg,g,e);fails++;}}while(0)

int main(void){
    /* T1: 8 GP-class varargs (objects/longs). 3 named consumed -> varargs fill gp[3..7] (5), rest spill. */
    {
        tl_va_frame f; memset(&f,0,sizeof f);
        uint64_t stk[8]; memset(stk,0,sizeof stk);
        for(int i=0;i<5;i++) f.gp[3+i]=0xA000+i;         /* varargs 0..4 in regs */
        for(int i=0;i<3;i++) stk[i]=0xA000+5+i;          /* varargs 5..7 on stack */
        f.stack=stk;
        tl_va_list ap; tl_va_start(&f,3,0,&ap);
        for(int i=0;i<8;i++){char m[16];sprintf(m,"T1 gp#%d",i);CKU(gnext(&ap),0xA000+i,m);}
    }
    /* T2: 10 double varargs. fill fp[0..7] (8), rest spill to stack. */
    {
        tl_va_frame f; memset(&f,0,sizeof f);
        double stk[8]; memset(stk,0,sizeof stk);
        for(int i=0;i<8;i++){double d=1.5+i; memcpy(&f.fp[i],&d,8);}  /* varargs 0..7 in regs */
        for(int i=0;i<2;i++) stk[i]=1.5+8+i;                           /* varargs 8..9 on stack */
        f.stack=stk;
        tl_va_list ap; tl_va_start(&f,3,0,&ap);
        for(int i=0;i<10;i++){char m[16];sprintf(m,"T2 d#%d",i);CKD(fnext(&ap),1.5+i,m);}
    }
    /* T3: mixed within regs: L,J,F,D,L,D (GP: 3 of them; FP: 3 of them). order preserved. */
    {
        tl_va_frame f; memset(&f,0,sizeof f);
        f.gp[3]=0x1111; f.gp[4]=0x2222; f.gp[5]=0x3333;              /* the 3 GP-class (L,J,L) */
        double d0=10.5,d1=20.5,d2=30.5; memcpy(&f.fp[0],&d0,8);memcpy(&f.fp[1],&d1,8);memcpy(&f.fp[2],&d2,8);
        tl_va_list ap; tl_va_start(&f,3,0,&ap);
        CKU(gnext(&ap),0x1111,"T3 L0"); CKU(gnext(&ap),0x2222,"T3 J");
        CKD(fnext(&ap),10.5,"T3 F");    CKD(fnext(&ap),20.5,"T3 D");
        CKU(gnext(&ap),0x3333,"T3 L1"); CKD(fnext(&ap),30.5,"T3 D2");
    }
    /* T4: BOTH overflow, declaration-ordered stack. Varargs (after 3 named):
       6 GP (g0..g5) + 10 FP (d0..d9). GP regs hold g0..g4 (gp[3..7]); g5 spills.
       FP regs hold d0..d7 (fp[0..7]); d8,d9 spill. Stack order = source order of spilled args.
       Pick a source order where spills interleave: ... then g5, d8, d9 at the tail -> stack=[g5,d8,d9]. */
    {
        tl_va_frame f; memset(&f,0,sizeof f);
        for(int i=0;i<5;i++) f.gp[3+i]=0xB000+i;            /* g0..g4 */
        for(int i=0;i<8;i++){double d=100.0+i; memcpy(&f.fp[i],&d,8);} /* d0..d7 */
        uint64_t stk[3]; stk[0]=0xB000+5;                   /* g5 */
        double d8=108.0,d9=109.0; memcpy(&stk[1],&d8,8); memcpy(&stk[2],&d9,8);
        f.stack=stk;
        tl_va_list ap; tl_va_start(&f,3,0,&ap);
        for(int i=0;i<5;i++){char m[16];sprintf(m,"T4 g#%d",i);CKU(gnext(&ap),0xB000+i,m);}
        CKU(gnext(&ap),0xB000+5,"T4 g5(stack)");            /* GP spill from stack */
        for(int i=0;i<8;i++){char m[16];sprintf(m,"T4 d#%d",i);CKD(fnext(&ap),100.0+i,m);}
        CKD(fnext(&ap),108.0,"T4 d8(stack)"); CKD(fnext(&ap),109.0,"T4 d9(stack)");
    }
    printf(fails? "\n== %d FAILURES ==\n":"\nALL VA-READER CHECKS PASSED\n", fails);
    return fails?1:0;
}
