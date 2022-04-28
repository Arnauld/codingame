use std::io;

macro_rules! parse_input {
    ($x:expr, $t:ident) => ($x.trim().parse::<$t>().unwrap())
}

#[derive(Debug, Clone)]
struct Vector {
    x: f32,
    y: f32,
}

#[derive(Debug, Clone)]
struct Point {
    x: i32,
    y: i32,
}

impl Point {
    fn new(x: i32, y: i32) -> Point {
        return Point { x, y };
    }

    fn translate(&self, vector: &Vector) -> Point {
        let x: f32 = self.x as f32 + vector.x;
        let y: f32 = self.y as f32 + vector.y;
        return Point { x: x as i32, y: y as i32 };
    }

    fn distance_to(&self, other: &Point) -> f32 {
        let dx = (self.x - other.x) as f32;
        let dy = (self.y - other.y) as f32;
        return (dx * dx + dy * dy).sqrt();
    }

    fn closest<'a>(&self, p1: &'a Point, p2: &'a Point) -> &'a Point {
        let d1 = self.distance_to(p1);
        let d2 = self.distance_to(p2);
        if d1 > d2 {
            return p2;
        }
        return p1;
    }
}

#[derive(Debug, Clone)]
struct Human {
    id: i32,
    position: Point,
}

#[derive(Debug, Clone)]
struct Zombie {
    id: i32,
    position: Point,
    next_position: Point,
}

#[derive(Debug, Clone)]
struct GameState {
    humans: Vec<Human>,
    zombies: Vec<Zombie>,
    hero: Point,
}


#[derive(Debug, Clone)]
struct KV<K, V> {
    k: K,
    v: V,
}

const ZOMBIE_MOVE: f32 = 400.0;
const HERO_MOVE: f32 = 1000.0;
const HERO_DAMAGE_RANGE: f32 = 2000.0;

fn nb_round_before_dying<'a>(human: &'a Human, zombies: &'a Vec<Zombie>) -> KV<i32, &'a Zombie> {
    zombies.iter()
        .map(|zombie| KV::<i32, &Zombie> {
            k: (zombie.position.distance_to(&human.position) / ZOMBIE_MOVE).ceil() as i32,
            v: zombie,
        })
        .min_by_key(|kv| kv.k)
        .unwrap()
}

impl GameState {
    fn human_most_in_danger(&self) -> Option<KV::<&Human, &Zombie>> {
        let zombies = &self.zombies;
        let humans = &self.humans;
        let hero = &self.hero;
        humans
            .into_iter()
            .map(|human| KV::<KV<i32, &Zombie>, &Human> {
                k: nb_round_before_dying(&human, zombies),
                v: human,
            })
            .filter(|kv| {
                let closest = hero.closest(&kv.v.position, &kv.k.v.position);
                let nb = ((hero.distance_to(closest) - HERO_DAMAGE_RANGE) / HERO_MOVE).ceil() as i32;

                eprintln!("h{} z{} :: to go #{} remaining #{}", &kv.v.id, &kv.k.v.id, nb, kv.k.k);

                nb <= kv.k.k
            })
            .min_by_key(|kv| kv.k.k)
            .map(|kv|
                KV::<&Human, &Zombie> {
                    k: kv.v,
                    v: kv.k.v,
                })
    }
}

//
//
//
//
fn read_hero_position() -> Point {
    let mut input_line = String::new();
    io::stdin().read_line(&mut input_line).unwrap();
    let inputs = input_line.split(" ").collect::<Vec<_>>();
    let x = parse_input!(inputs[0], i32);
    let y = parse_input!(inputs[1], i32);
    return Point::new(x, y);
}

fn read_human_states() -> Vec<Human> {
    let mut humans = Vec::new();

    let mut input_line = String::new();
    io::stdin().read_line(&mut input_line).unwrap();
    let human_count = parse_input!(input_line, i32);
    for _i in 0..human_count as usize {
        let mut input_line = String::new();
        io::stdin().read_line(&mut input_line).unwrap();
        let inputs = input_line.split(" ").collect::<Vec<_>>();
        let id = parse_input!(inputs[0], i32);
        let x = parse_input!(inputs[1], i32);
        let y = parse_input!(inputs[2], i32);
        humans.push(Human {
            id,
            position: Point::new(x, y),
        })
    }
    return humans;
}

fn read_zombie_states() -> Vec<Zombie> {
    let mut zombies = Vec::new();

    let mut input_line = String::new();
    io::stdin().read_line(&mut input_line).unwrap();
    let zombie_count = parse_input!(input_line, i32);
    for _i in 0..zombie_count as usize {
        let mut input_line = String::new();
        io::stdin().read_line(&mut input_line).unwrap();
        let inputs = input_line.split(" ").collect::<Vec<_>>();
        let id = parse_input!(inputs[0], i32);
        let x = parse_input!(inputs[1], i32);
        let y = parse_input!(inputs[2], i32);
        let nx = parse_input!(inputs[3], i32);
        let ny = parse_input!(inputs[4], i32);
        zombies.push(Zombie {
            id,
            position: Point::new(x, y),
            next_position: Point::new(nx, ny),
        })
    }
    return zombies;
}

fn read_game_state() -> GameState {
    let hero = read_hero_position();
    let humans = read_human_states();
    let zombies = read_zombie_states();
    return GameState {
        hero,
        humans,
        zombies,
    };
}

/**
 * Save humans, destroy zombies!
 **/
fn main() {

    // game loop
    loop {
        let game_state = read_game_state();

        let found: Option<KV::<&Human, &Zombie>> = game_state.human_most_in_danger();

        let target: &Point = found.map_or(&game_state.hero, |kv| {
            game_state.hero.closest(&kv.k.position, &kv.v.position)
        });
        println!("{} {}", target.x, target.y); // Your destination coordinates

        // Write an action using println!("message...");
        // To debug: eprintln!("Debug message...");
    }
}
